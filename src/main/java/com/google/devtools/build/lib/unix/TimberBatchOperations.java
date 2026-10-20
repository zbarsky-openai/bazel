// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.unix;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.Blocker;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import javax.annotation.Nullable;

/** Optional Timber creation ioctl, called only on Linux x86-64/AArch64 with JDK 25 or newer. */
final class TimberBatchOperations {
  private static final int MAX_ENTRIES = 4096;
  private static final int MAX_BYTES = 1024 * 1024;
  private static final long TIMBER_MAGIC = 0x54494d4bL;
  private static final long CREATE_IOCTL = 0xc020b702L;
  private static final int EINTR = 4;
  private static final int ENOTTY = 25;

  // TimberBatchSymlink: u64 entries, strings; u32 count, string_bytes, flags, reserved.
  private static final int REQUEST_BYTES = 32;
  // TimberBatchSymlinkEntry: i32 dirfd, error; u32 name_offset, target_offset.
  private static final int ENTRY_BYTES = 16;

  private static final class Libc {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfLong C_LONG =
        (ValueLayout.OfLong) LINKER.canonicalLayouts().get("long");
    private static final long ERRNO_OFFSET =
        Linker.Option.captureStateLayout().byteOffset(groupElement("errno"));
    private static final MethodHandle OPEN =
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("open").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            Linker.Option.firstVariadicArg(2),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle FSTATFS =
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("fstatfs").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle CLOSE =
        LINKER
            .downcallHandle(
                LINKER.defaultLookup().find("close").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT))
            .asType(MethodType.methodType(void.class, int.class));
    // syscall(long number, ...), unlike ioctl(), has the same declaration in glibc and musl.
    // Pass the fd, request and pointer as machine words, with the pointer owned by the arena.
    private static final MethodHandle IOCTL =
        LINKER.downcallHandle(
            LINKER.defaultLookup().find("syscall").orElseThrow(),
            FunctionDescriptor.of(C_LONG, C_LONG, C_LONG, C_LONG, C_LONG),
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));
    // Linux asm/unistd_64.h (x86-64), asm-generic/unistd.h (AArch64).
    private static final long SYS_IOCTL =
        System.getProperty("os.arch").equals("aarch64") ? 29L : 16L;
    // O_RDONLY is zero; O_DIRECTORY differs between the two Linux ABIs. O_CLOEXEC is shared.
    private static final int OPEN_FLAGS =
        (System.getProperty("os.arch").equals("aarch64") ? 040000 : 0200000) | 02000000;

    private static int errno(MemorySegment state) {
      return state.get(JAVA_INT, ERRNO_OFFSET);
    }
  }

  static int createDirectories(String root, List<PathFragment> paths) throws IOException {
    int[] errors = create(root, paths, null);
    if (errors == null) {
      return -1;
    }
    int created = 0;
    while (created < errors.length && errors[created] == 0) {
      created++;
    }
    return created;
  }

  @Nullable
  static int[] createSymlinks(String root, List<PathFragment> paths, List<PathFragment> targets)
      throws IOException {
    return create(root, paths, targets);
  }

  private static byte[] checkedBytes(String root, @Nullable String value) throws IOException {
    if (value == null || value.indexOf('\0') >= 0) {
      throw new InvalidArgumentIOException(root + " (Invalid argument)");
    }
    // Internal Bazel paths are raw bytes carried by Latin-1 strings, not Unicode to encode as
    // UTF-8.
    return StringUnsafe.getInternalStringBytes(value);
  }

  @Nullable
  private static int[] create(
      String root, List<PathFragment> paths, @Nullable List<PathFragment> targets)
      throws IOException {
    byte[] rootBytes = checkedBytes(root, root);
    int count = paths.size();
    if (count > MAX_ENTRIES || (targets != null && targets.size() != count)) {
      throw new InvalidArgumentIOException(root + " (Invalid argument)");
    }
    byte[][] names = new byte[count][];
    byte[][] linkTargets = targets == null ? null : new byte[count][];
    long stringBytes = 0;
    for (int i = 0; i < count; i++) {
      PathFragment path = paths.get(i);
      names[i] = checkedBytes(root, path == null ? null : path.getPathString());
      stringBytes += names[i].length + 1L;
      if (targets != null) {
        PathFragment target = targets.get(i);
        linkTargets[i] = checkedBytes(root, target == null ? null : target.getSafePathString());
        stringBytes += linkTargets[i].length;
      }
      stringBytes++; // Empty target for directory requests, otherwise the target's terminator.
      if (stringBytes > MAX_BYTES) {
        throw new IOException(root + " (Argument list too long)");
      }
    }

    // Allocate on the executing worker: a confined arena cannot be handed across executor threads.
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment rootPath = arena.allocate(rootBytes.length + 1L);
      rootPath.asSlice(0, rootBytes.length).copyFrom(MemorySegment.ofArray(rootBytes));
      MemorySegment entries = arena.allocate((long) count * ENTRY_BYTES, 4);
      MemorySegment strings = arena.allocate(stringBytes);
      MemorySegment request = arena.allocate(REQUEST_BYTES, 8);
      MemorySegment state = arena.allocate(Linker.Option.captureStateLayout());
      // glibc and musl statfs on these Linux64 ABIs: 120 bytes, aligned to 8; f_type is first.
      MemorySegment statfs = arena.allocate(120, 8);
      long offset = 0;
      for (int i = 0; i < count; i++) {
        long entry = (long) i * ENTRY_BYTES;
        entries.set(JAVA_INT, entry + 8, (int) offset);
        MemorySegment.copy(MemorySegment.ofArray(names[i]), 0, strings, offset, names[i].length);
        offset += names[i].length + 1L;
        entries.set(JAVA_INT, entry + 12, (int) offset);
        if (linkTargets != null) {
          MemorySegment.copy(
              MemorySegment.ofArray(linkTargets[i]), 0, strings, offset, linkTargets[i].length);
          offset += linkTargets[i].length;
        }
        offset++;
      }
      request.set(JAVA_LONG, 0, entries.address());
      request.set(JAVA_LONG, 8, strings.address());
      request.set(JAVA_INT, 16, count);
      request.set(JAVA_INT, 20, (int) stringBytes);
      request.set(JAVA_INT, 24, targets == null ? 1 : 0);
      // Arena allocation zeroes error fields, terminators and the reserved request word.
      var blocker = Blocker.begin();
      try {
        int fd;
        do {
          fd = (int) Libc.OPEN.invokeExact(state, rootPath, Libc.OPEN_FLAGS);
        } while (fd < 0 && Libc.errno(state) == EINTR);
        if (fd < 0) {
          return null;
        }
        try {
          if ((int) Libc.FSTATFS.invokeExact(fd, statfs) < 0
              || statfs.get(JAVA_LONG, 0) != TIMBER_MAGIC) {
            return null;
          }
          for (int i = 0; i < count; i++) {
            entries.set(JAVA_INT, (long) i * ENTRY_BYTES, fd);
          }
          long result =
              (long)
                  Libc.IOCTL.invokeExact(
                      state, Libc.SYS_IOCTL, (long) fd, CREATE_IOCTL, request.address());
          if (result < 0) {
            int error = Libc.errno(state);
            if (error == ENOTTY) {
              return null; // Timber guarantees no writes when the operation is unsupported.
            }
            // Other failures can follow writes: never retry the ioctl or request scalar fallback.
            throw new IOException(root + " (Timber creation ioctl failed, errno " + error + ")");
          }
          int[] errors = new int[count];
          for (int i = 0; i < count; i++) {
            errors[i] = entries.get(JAVA_INT, (long) i * ENTRY_BYTES + 4);
          }
          return errors;
        } finally {
          // Match the existing close policy: ignore the result and never retry close after EINTR.
          Libc.CLOSE.invokeExact(fd);
        }
      } catch (IOException | RuntimeException | Error e) {
        throw e;
      } catch (Throwable e) {
        // A native downcall cannot throw a checked exception; a descriptor mismatch is a bug.
        throw new IllegalStateException("Timber native call failed", e);
      } finally {
        Blocker.end(blocker);
      }
    }
  }

  private TimberBatchOperations() {}
}
