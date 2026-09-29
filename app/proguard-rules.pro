# Keep the accessibility service, its config entry points and engine classes.
-keep class com.stopapps.app.accessibility.StopAccessService { *; }
-keep class com.stopapps.app.accessibility.ForceStopEngine { *; }
-keep class com.stopapps.app.service.StopRunnerService { *; }
# Keep all app classes (the app's own code is small; the size win comes from
# shrinking the libraries). This guards ViewModels, the Application class,
# and anything reached via framework reflection.
-keep class com.stopapps.app.** { *; }
