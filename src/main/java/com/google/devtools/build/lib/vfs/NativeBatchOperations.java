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
package com.google.devtools.build.lib.vfs;

import static com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly;

import com.google.devtools.build.lib.profiler.Profiler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/** Optional filesystem-native creation; ordinary operations handle unsupported filesystems. */
public final class NativeBatchOperations {
  private static final int MAX_ENTRIES = 4096;
  private static final int MAX_BYTES = 1024 * 1024;
  private static final int SYMLINK_CONCURRENCY = 4;
  private final Executor executor;

  public NativeBatchOperations() {
    this(Thread::startVirtualThread);
  }

  public NativeBatchOperations(Executor executor) {
    this.executor = executor;
  }

  @SuppressWarnings("try")
  public int createDirectories(Path root, List<PathFragment> directories) throws IOException {
    if (directories.isEmpty()) {
      return -1;
    }
    int created = 0;
    while (created < directories.size()) {
      int end = created;
      int bytes = 0;
      while (end < directories.size() && end - created < MAX_ENTRIES) {
        long size = (long) directories.get(end).getPathString().length() + 2;
        if (size > MAX_BYTES - bytes) {
          break;
        }
        bytes += (int) size;
        end++;
      }
      if (end == created) {
        return created == 0 ? -1 : created;
      }
      int count;
      try (var ignored = Profiler.instance().profile("Native batch mkdir")) {
        count =
            root.getFileSystem()
                .createDirectoriesBatch(root.asFragment(), directories.subList(created, end));
      }
      if (count < -1 || count > end - created) {
        throw new IOException("Native batch mkdir returned an invalid created count");
      }
      if (count < end - created) {
        return count < 0 && created == 0 ? -1 : created + Math.max(count, 0);
      }
      created = end;
    }
    return created;
  }

  public void createSymlinks(Path root, Map<PathFragment, PathFragment> symlinks)
      throws IOException {
    // Give medium trees useful parallelism while keeping tiny batches inline and bounding overhead.
    int batchEntries =
        Math.min(MAX_ENTRIES, Math.max(512, 1 + (symlinks.size() - 1) / SYMLINK_CONCURRENCY));
    var completed = new ExecutorCompletionService<SymlinkBatch>(executor);
    Set<Future<SymlinkBatch>> pending = new HashSet<>();
    try {
      List<PathFragment> paths = new ArrayList<>();
      List<PathFragment> targets = new ArrayList<>();
      int bytes = 0;
      for (var entry : symlinks.entrySet()) {
        long size =
            (long) entry.getKey().getPathString().length()
                + entry.getValue().getSafePathString().length()
                + 2;
        if (!paths.isEmpty() && (paths.size() == batchEntries || size > MAX_BYTES - bytes)) {
          List<PathFragment> batchPaths = paths;
          List<PathFragment> batchTargets = targets;
          pending.add(completed.submit(() -> executeSymlinkBatch(root, batchPaths, batchTargets)));
          if (pending.size() == SYMLINK_CONCURRENCY) {
            finishSymlinkBatch(root, pending, completed);
          }
          paths = new ArrayList<>();
          targets = new ArrayList<>();
          bytes = 0;
        }
        if (size > MAX_BYTES) {
          FileSystemUtils.ensureSymbolicLink(root.getRelative(entry.getKey()), entry.getValue());
        } else {
          paths.add(entry.getKey());
          targets.add(entry.getValue());
          bytes += (int) size;
        }
      }
      if (!paths.isEmpty()) {
        // Single-batch trees avoid executor overhead. The caller also handles the final chunk.
        reconcileSymlinkBatch(root, executeSymlinkBatch(root, paths, targets));
      }
      while (!pending.isEmpty()) {
        finishSymlinkBatch(root, pending, completed);
      }
    } finally {
      // A mutating syscall cannot be safely cancelled. Finish submitted work before returning,
      // including on interruption or an ambiguous failure, without replaying any batch.
      for (var future : pending) {
        try {
          getUninterruptibly(future);
        } catch (ExecutionException ignored) {
          // The failure that caused draining is already propagating to the caller.
        }
      }
    }
  }

  private record SymlinkBatch(List<PathFragment> paths, List<PathFragment> targets, int[] errors) {}

  private void finishSymlinkBatch(
      Path root,
      Set<Future<SymlinkBatch>> pending,
      ExecutorCompletionService<SymlinkBatch> completed)
      throws IOException {
    try {
      var future = completed.take();
      pending.remove(future);
      reconcileSymlinkBatch(root, future.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Native batch symlink interrupted", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException ioException) {
        throw ioException;
      }
      throw new IOException("Native batch symlink failed", e.getCause());
    }
  }

  @SuppressWarnings("try")
  private SymlinkBatch executeSymlinkBatch(
      Path root, List<PathFragment> paths, List<PathFragment> targets) throws IOException {
    int[] errors;
    try (var ignored = Profiler.instance().profile("Native batch symlink")) {
      errors = root.getFileSystem().createSymlinksBatch(root.asFragment(), paths, targets);
    }
    if (errors != null && errors.length != paths.size()) {
      throw new IOException("Native batch symlink returned an invalid result count");
    }
    return new SymlinkBatch(paths, targets, errors);
  }

  private static void reconcileSymlinkBatch(Path root, SymlinkBatch batch) throws IOException {
    for (int i = 0; i < batch.paths().size(); i++) {
      if (batch.errors() == null || batch.errors()[i] != 0) {
        if (batch.errors() == null) {
          try {
            root.getRelative(batch.paths().get(i)).createSymbolicLink(batch.targets().get(i));
            continue;
          } catch (IOException e) {
            // Unsupported filesystems retain the create-first path for missing links.
          }
        }
        FileSystemUtils.ensureSymbolicLink(
            root.getRelative(batch.paths().get(i)), batch.targets().get(i));
      }
    }
  }
}
