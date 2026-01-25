# Cyber Phone
<img alt="Logo" src="graphics/icon.webp" width="120" />

Cyber Phone is a privacy-focused phone, contacts, and messaging app for modern Android. It combines dialer, SMS, caller ID enrichment, call screening, spam controls, and mesh-ready communications in one app.

**Features**
- Default phone app with dialer, call history, and rich in-call UI
- Fully integrated system contacts with in-app editing and quick add from calls/SMS
- SMS conversations with category filters (Main/OTP/Spam), search, unread indicators, and status checks
- Call and SMS blocking: number rules, patterns, neighbor spoofing handling, and cached community lists
- Caller ID enrichment using libphonenumber geocoder and carrier lookup
- Spam handling: suppressed spam notifications and auto-declined spam calls
- End-to-end SMS encryption with key exchange; keys stored in contacts and editable
- Reticulum-based mesh messaging and calls with configurable modes (mesh-only, fallback, standard)
- Rich settings and modern Material UI

**Build**
- Open the project in Android Studio and build the `app` module.
- Ensure Android SDK and required build tools are installed.
- For release signing, provide a `keystore.properties` file (not tracked in git).

**License**
AGPL-3.0 (includes blocking engine code derived from Yet Another Call Blocker).

**Acknowledgements**
- Fossify (https://www.fossify.org)
- Yet Another Call Blocker (https://gitlab.com/xynngh/YetAnotherCallBlocker)
- Reticulum Network Stack (https://github.com/markqvist/Reticulum)
- LXMF (https://github.com/markqvist/LXMF)
- libphonenumber (https://github.com/google/libphonenumber)
