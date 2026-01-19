This is the full public code for my Android app on Google Play: "Smart Pantry".

Here you will find: 

•the kotlin classes I wrote to define and use navigation screens, the inventory item/list data structure, the barcode scanner, permissions, cloud storage functions, alarms, etc.

•all of my vector drawable icon resources as well as the "values" xaml files for colors, theme, & the app name string (for displaying "Smart Pantry" instead of the full package name: "Smart Pantry Kotlin Compose" anywhere applicable.)

•the app's manifest which incorporates all required permissions (camera for barcode scan,.notifications and alarms, internet permission for cloud storage, etc.) while disabling Google's GMS ad tracking permission.

•my gradle files and my libs.versions.toml file, which helps to keep my app/module-level gradle clean.

•my gitignore file, which makes sure to exclude keystore files just in case they ever accidentally ended up in the project folder.
