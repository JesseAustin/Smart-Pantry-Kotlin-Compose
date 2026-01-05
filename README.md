To build this project from GitHub in Android Studio:

Add the project files to an empty project:
-Drag all folders in this repo into your project folder.

Add your app icon (mipmaps):
-Right-click the res folder in Android Studio
-Select New → Image Asset
-In the dropdown, choose Launcher Icons (Adaptive and Legacy)
-Add your own icon(s). This will recreate the mipmap-* folders needed to compile.

Set up Firebase:
-Create a new Firebase project in the Firebase Console
-Add an Android app to the project using your app’s package name (different from the one in this repo; you’ll need to update it in the code, e.g., manifest and classes)
-Download the generated google-services.json from firebase console (help: https://firebase.google.com/docs/android/setup#add-config-file)
-Place it in the app/ folder of the project

Sync Gradle:
-Click File → Sync Project with Gradle Files to ensure dependencies are loaded

Run the app:
-Build and run the project on an emulator or device
