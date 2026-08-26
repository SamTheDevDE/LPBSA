# Publishing LPBSA

1. Update `CHANGELOG.md`.
2. Choose a semantic release version.
3. Set `version` in `gradle.properties` and remove `-SNAPSHOT`.
4. Run `./gradlew clean build`.
5. Test the JAR on a real Velocity proxy.
6. Test current LuckPerms permissions and inherited groups.
7. Test an allowed backend.
8. Test a denied backend transfer.
9. Test forced-host denial.
10. Test a valid and broken fallback.
11. Tag the commit as `vX.Y.Z`.
12. Push the tag.
13. Verify the GitHub release and attached JAR.
14. Verify the published SHA-256 checksum.

Future Modrinth or Hangar publication should only be configured after real project IDs and protected API tokens exist. Do not commit tokens.
