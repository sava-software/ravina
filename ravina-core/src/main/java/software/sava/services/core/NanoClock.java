package software.sava.services.core;

public interface NanoClock {

  NanoClock SYSTEM = new NanoClock() {
    @Override
    public long nanoTime() {
      return System.nanoTime();
    }

    @Override
    public void sleep(final long millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  };

  long nanoTime();

  void sleep(final long millis) throws InterruptedException;
}
