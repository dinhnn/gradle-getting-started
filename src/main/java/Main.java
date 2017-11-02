import io.vertx.core.Launcher;

public class Main {
  public static void main(String... args) throws Exception {
    Launcher.main(new String[] { "run", com.dinhnn.httpbridge.HttpProxyServer.class.toString() });
  }
}