import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/** Removes MethodParameters attributes (javac 21 emits nameless entries that crash D8 8.2). */
public class StripParams {
  public static void main(String[] a) throws Exception {
    try (ZipInputStream in = new ZipInputStream(new FileInputStream(a[0]));
         ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]))) {
      ZipEntry e; int n = 0;
      while ((e = in.getNextEntry()) != null) {
        if (e.isDirectory()) continue;
        byte[] data = in.readAllBytes();
        if (e.getName().endsWith(".class")) {
          ClassReader cr = new ClassReader(data);
          ClassWriter cw = new ClassWriter(0);
          cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
              return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, name, desc, sig, ex)) {
                @Override public void visitParameter(String pn, int pacc) { /* drop */ }
              };
            }
          }, 0);
          data = cw.toByteArray(); n++;
        }
        out.putNextEntry(new ZipEntry(e.getName()));
        out.write(data); out.closeEntry();
      }
      System.out.println("processed " + n + " classes");
    }
  }
}
