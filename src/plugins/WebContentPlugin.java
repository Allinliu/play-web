package plugins;

import models.WebPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.Play;
import play.PlayPlugin;
import play.cache.CacheFor;
import play.db.jpa.JPA;
import play.i18n.Lang;
import play.mvc.Http;
import play.mvc.Router;
import play.mvc.Scope;

import java.lang.reflect.Method;
import java.util.List;

import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.substring;

public class WebContentPlugin extends PlayPlugin {
  private static final Logger logger = LoggerFactory.getLogger(WebContentPlugin.class);

  public static final String WEB_CONTENT_METHOD = "serveContent";
  public static final String WEB_CACHED_CONTENT_METHOD = "serveContentCached";
  public static final String WEB_NEWS_METHOD = "news";
  public static final String WEB_REDIRECT_ALIAS_METHOD = "redirectAlias";

  private long lastModified;
  private int genericRouteIndex;

  public static boolean cacheEnabled() {
    return "true".equals(Play.configuration.getProperty("web.cacheEnabled", String.valueOf(Play.mode.isProd())));
  }

  @Override public void onApplicationStart() {
    genericRouteIndex = Router.routes.size();
    for (int i = 0; i < Router.routes.size(); i++) {
      Router.Route route = Router.routes.get(i);
      if (route.action.startsWith("{controller}")) {
        genericRouteIndex = i; break;
      }
    }

    addWebRoutes(WebPage.ROOT.children());

    for (WebPage page : WebPage.all()) {
      String alias = page.metadata.getProperty("alias");
      if (isNotEmpty(alias)) {
        if (!alias.startsWith("/")) alias = "/" + alias;
        Router.appendRoute("GET", alias + "/?", "Web." + WEB_REDIRECT_ALIAS_METHOD, "{path:'" + page.path + "'}", null, null, 0);
      }

      if ("news".equals(page.metadata.getProperty("template"))) {
        WebPage.News.pathPrefixes.add(page.path.replaceFirst("/$", ""));
        Router.addRoute(genericRouteIndex, "GET", page.path + ".*", "Web." + WEB_NEWS_METHOD, null);
      }
    }

    lastModified = WebPage.ROOT.dir.lastModified();
  }

  @Override public void afterApplicationStart() {
    checkWebMethod(WEB_CONTENT_METHOD);
    checkWebMethod(WEB_CACHED_CONTENT_METHOD);
    checkWebMethod(WEB_NEWS_METHOD, String.class);
    checkWebMethod(WEB_REDIRECT_ALIAS_METHOD, String.class);
  }

  @Override public void detectChange() {
    if (WebPage.ROOT.dir.lastModified() > lastModified) {
      logger.info(WebPage.ROOT.dir + " change detected, reloading web routes");
      onApplicationStart();
    }
  }

  private static boolean shouldStartTx() {
    return "always".equals(Play.configuration.getProperty("web.tx", "logged-in")) || isLoggedIn();
  }

  private static boolean isLoggedIn() {
    return Scope.Session.current().contains("username");
  }

  @Override public void beforeActionInvocation(Method actionMethod) {
    if (actionMethod.isAnnotationPresent(SetLangByURL.class))
      setLangByURL();
    if (cacheEnabled() && actionMethod.isAnnotationPresent(CacheFor.class))
      Http.Response.current().cacheFor("12h");
    Scope.RenderArgs.current().put("rootPage", WebPage.rootForLocale());

    if ("controllers.Web".equals(actionMethod.getDeclaringClass().getName()) && shouldStartTx()) {
      JPA.startTx(JPA.DEFAULT, true);
      Http.Request.current().args.put("webTxStarted", true);
    }
  }

  @Override public void afterActionInvocation() {
    if (Http.Request.current().args.get("webTxStarted") != null)
      JPA.closeTx(JPA.DEFAULT);
  }

  private void setLangByURL() {
    String locale = Lang.get();
    String path = Http.Request.current().path;
    String expectedLocale = path.matches("/[a-z]{2}/.*") ? path.substring(1,3) : Play.langs.get(0);
    if (!expectedLocale.equals(locale)) {
      Lang.change(expectedLocale);
      // play puts only session cookie, let's have a longer one
      Http.Response.current().setCookie(Play.configuration.getProperty("application.lang.cookie", "PLAY_LANG"), expectedLocale, "10000d");
    }
  }

  void addWebRoutes(List<WebPage> pages) {
    for (WebPage page : pages) {
      String path = page.path;
      if (path.endsWith("/")) path = substring(path, 0, -1);
      Router.addRoute(genericRouteIndex, "GET", path + "/?.*", "Web." + WEB_CONTENT_METHOD, null);
      if (cacheEnabled())  // cache top-level pages on production
        Router.addRoute(genericRouteIndex, "GET", path + "/", "Web." + WEB_CACHED_CONTENT_METHOD, null);
    }
  }

  private static void checkWebMethod(String methodName, Class... argTypes) {
    try {
      Play.classloader.loadClass("controllers.Web").getMethod(methodName, argTypes);
    }
    catch (Exception e) {
      throw new RuntimeException("Invalid method name: " + methodName, e);
    }
  }
}
