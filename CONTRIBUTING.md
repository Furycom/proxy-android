# Contributing to Proxy

Thank you for taking the time to help! Whether you found a bug, have an idea, or want to write code, every contribution is welcome — including from people who have never contributed to an open-source project before.

> 🇫🇷 Vous pouvez écrire en français : les issues, idées et contributions en français sont les bienvenues.

## Ask a question

Not sure whether something is a bug? Just want to know if your phone will work? Start a thread in [Discussions](https://github.com/Furycom/proxy-android/discussions) — no question is too small.

## Report a bug

1. Check the [existing issues](https://github.com/Furycom/proxy-android/issues) first — someone may already have reported it.
2. Open a [new bug report](https://github.com/Furycom/proxy-android/issues/new?template=bug_report.yml). The form asks for:
   - your phone model and Android version (Settings → About phone),
   - the app version (shown in Settings → Apps → Proxy),
   - what happened, and what you expected instead,
   - the steps to make it happen again.
3. Screenshots or a short screen recording help a lot.

Please remove any personal information (phone number, location, account names) from screenshots before posting.

## Suggest an idea

Open a [feature request](https://github.com/Furycom/proxy-android/issues/new?template=feature_request.yml). Tell us what you would like to do and why it matters to you — the "why" is often more useful than the exact solution.

## Translations

The interface is currently in French only. Translations are very welcome, starting with English. All user-facing text lives in Kotlin source files under `app/src/main/java/`; if you would like to translate, open an issue or a discussion first so we can agree on how to organize the strings.

## Contribute code

1. Fork the repository and create a branch from `main`.
2. Build the app with [`build.ps1`](build.ps1) — see [Build from source](README.md#build-from-source) in the README. You need your own signing key; none is included.
3. Keep changes focused: one fix or feature per pull request.
4. Test on a real phone with a real device if you can, and say which phone and Android version you used.
5. Open a pull request and fill in the short template.

### Ground rules for code

- **Never touch the device's firmware**, heater tuning or factory settings. The app only uses the device's normal Bluetooth commands, and it must stay that way.
- **No Internet access, no trackers, no analytics, no ads.** The app does not request the `INTERNET` permission, and pull requests that add it will not be accepted.
- **No Google Play Services.** The app must keep working on phones without Google services, such as Huawei phones.
- **Android 10 (API 29) stays supported.** Supporting older phones is the reason this app exists.
- **No third-party libraries** unless there is a very good reason — discuss it in an issue first.
- **Original code only.** Do not copy code from the official app or from projects whose license is not compatible with MIT.

By contributing, you agree that your contribution is licensed under the [MIT License](LICENSE).

## Be kind

Many people here are not developers — they got a Proxy as a gift and just want it to work on their phone. Please be patient, friendly and respectful with everyone.
