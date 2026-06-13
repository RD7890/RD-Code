# Keystore is NOT stored here — it lives as SIGNING_KEYSTORE_BASE64 GitHub secret.
# The CI workflow decodes it at build time.
# For local builds:
#   1. Export your keystore as PKCS12 (openssl pkcs12 ...)
#   2. Set env vars before running gradlew:
#      export SIGNING_STORE_FILE=/path/to/ryzix.p12
#      export SIGNING_STORE_PASSWORD=yourpassword
#      export SIGNING_KEY_ALIAS=ryzix-release
#      export SIGNING_KEY_PASSWORD=yourpassword
