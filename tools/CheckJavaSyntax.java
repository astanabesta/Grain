import com.sun.source.util.JavacTask;
import java.io.*;
import java.util.*;
import javax.tools.*;

public class CheckJavaSyntax {
  public static void main(String[] args) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager manager =
        compiler.getStandardFileManager(diagnostics, null, null)) {
      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  manager,
                  diagnostics,
                  List.of("-proc:none"),
                  null,
                  manager.getJavaFileObjects(args));
      task.parse();
      boolean failed = false;
      for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
        System.out.println(d);
        if (d.getKind() == Diagnostic.Kind.ERROR) failed = true;
      }
      if (failed) System.exit(1);
      System.out.println(
          "Parsed "
              + args.length
              + " Java files. Syntax only; Android linking is done by GitHub Actions.");
    }
  }
}
