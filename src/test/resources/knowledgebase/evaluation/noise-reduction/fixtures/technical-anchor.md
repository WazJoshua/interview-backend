# Troubleshooting

## AUTH_001 invalid token

Use the following command to reproduce the issue safely:

```bash
./gradlew test
```

Check the config path `/etc/interviewj/app.yml` and confirm the token refresh window is correct.

The appendix sample below is reference-only and should not override the protected anchor:

token=abcdef0123456789
payload={"mode":"sample","retry":3}
