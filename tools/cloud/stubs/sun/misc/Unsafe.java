package sun.misc;

public final class Unsafe {
  private Unsafe() {}
  public static Unsafe getUnsafe() { throw new RuntimeException("stub"); }
  public long objectFieldOffset(java.lang.reflect.Field f) { throw new RuntimeException("stub"); }
  public boolean compareAndSwapInt(Object o, long off, int e, int n) { throw new RuntimeException("stub"); }
  public boolean compareAndSwapLong(Object o, long off, long e, long n) { throw new RuntimeException("stub"); }
  public boolean compareAndSwapObject(Object o, long off, Object e, Object n) { throw new RuntimeException("stub"); }
  public void putObject(Object o, long off, Object v) { throw new RuntimeException("stub"); }
  public int arrayBaseOffset(Class<?> c) { throw new RuntimeException("stub"); }
  public int arrayIndexScale(Class<?> c) { throw new RuntimeException("stub"); }
  public long getLong(Object o, long off) { throw new RuntimeException("stub"); }
  public void putLong(Object o, long off, long v) { throw new RuntimeException("stub"); }
}
