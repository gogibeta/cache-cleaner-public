# Keep the accessibility service, its config entry points and engine classes.
-keep class com.cachecleaner.app.accessibility.CacheAccessService { *; }
-keep class com.cachecleaner.app.accessibility.CacheClearEngine { *; }
-keep class com.cachecleaner.app.service.CacheRunnerService { *; }
# Keep all app classes (the app's own code is small; the size win comes from
# shrinking the libraries). This guards ViewModels, the Application class,
# and anything reached via framework reflection.
-keep class com.cachecleaner.app.** { *; }
