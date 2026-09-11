# Stable Translation Test signing

The test workflow requires the repository Actions secret
`TRANSLATION_TEST_KEYSTORE_BASE64`. Keep the same secret for future builds.
The keystore itself must stay private; do not commit it.

On a computer with Java installed, generate a dedicated test key once:

```sh
keytool -genkeypair -keystore translation-test.jks -storetype JKS -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=WaEnhancer Translation Test'
base64 < translation-test.jks
```

In GitHub repository Settings > Secrets and variables > Actions, create
`TRANSLATION_TEST_KEYSTORE_BASE64` with the complete base64 output as its value.
Keep a private backup of the keystore. Never use this key for production releases.

Re-run the Translation UI test APK workflow after adding the secret.
The workflow checks the test package ID, label, and signing certificate before
uploading the APK. It fails if the secret is missing instead of generating an
incompatible replacement key.

Installations signed with an older runner-generated key require uninstalling
only WaEnhancer Translation Test once. Uninstalling clears its app settings.
Install the new APK, re-enable the module in LSPosed, and configure it again.
Future test builds with the same secret can update that installation.
