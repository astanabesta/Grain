# Workspace footprint

No Android SDK or Flutter SDK was installed for this project, and no native APK build was run locally.

Checked `/home/user/Android`, `/home/user/android-sdk`, `/home/user/.android`, `/opt`, and common Android SDK directory names under the workspace. No downloaded Android SDK was found to remove. The earlier prototype work installed browser-testing tools; their cache/dependency directories were not present after the workspace reconnected.

The project intentionally contains source, a self-contained HTML design reference, Gradle configuration, and CI instructions only. Android SDK/build tools and Gradle are installed in the ephemeral GitHub Actions runner. No build outputs or downloaded dependencies are included in the source ZIP.

The existing `camera-repo` reference checkout and original HTML design files are left untouched.
