## Java Threads 
### Fixed Thread Pool
```
// Creates a number of threads in a pool, and tasks distributed amongst those threads as they become available.

ExecutorService executorService = ExecutorService.newFixedThreadPool(10);

for (int i = 0; i < 100; i++) {
  final int index = i;
  executorService.submit(() -> {
    log.info("Task: " + index + " is running in thread: " + Thread.currentThread().getName());
  });
}

executorService.shutdown();

```
