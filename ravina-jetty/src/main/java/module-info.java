module software.sava.ravina_jetty {
  requires org.eclipse.jetty.io;
  requires transitive org.eclipse.jetty.http;
  requires transitive org.eclipse.jetty.server;
  requires transitive org.eclipse.jetty.util;

  exports software.sava.services.jetty.handlers;
}
