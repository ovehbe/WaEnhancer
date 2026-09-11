# Google Translate chat UI (experimental)

Enable Google Translate in WaEnhancer and restart WhatsApp. Open WhatsApp's home overflow menu → Google Translate for the global default. Open a contact/group overflow menu → Google Translate for an override, explicit Off, or Use global default. Select a language to enable automatic translation. Long-press a text message → Translate with Google for manual translation.

Incoming text is sent to Google when rendered in a conversation. Original message content stays in the database and remains visible above the translation. Outgoing messages are not automatically translated. Language selection and automatic enablement are combined in this first UI. Defaults start Off; native WhatsApp translation settings are not migrated.

The catalog contains 194 entries from Google's NMT language list, retrieved 2026-09-11: https://docs.cloud.google.com/translate/docs/languages (CC BY 4.0). WaEnhancer uses the pre-existing consumer GTX endpoint, not Cloud Translation; availability of every listed language on that endpoint is unverified.

## Device acceptance checks

- Start with automatic translation Off: no automatic requests or translated bubbles.
- Enable English globally. Receive Indonesian/Turkish text and verify automatic rendering and preserved originals.
- Choose Turkish for one contact and Arabic for a group. Other chats must retain English. Group preference must use the group ID, not the sender ID.
- Set one chat Off, then Use global default; verify restoration and inheritance.
- Search for Indonesian, Turkish, Arabic and Chinese variants. Selection survives restart.
- Scroll rapidly between chats while requests are pending, including identical message text. No translations may appear in the wrong bubble/chat.
- Change language or turn Off while a request is pending; old results must not render.
- Rebind/edit a message and verify no duplicated translation or lost original formatting.
- Long-press and translate with automatic mode Off and On. Check coexistence with Copy Selection Message.
- Disconnect the network: original text stays readable, manual translation reports failure, repeated binds do not continuously retry failed requests.
- Disable the WaEnhancer toggle and restart: custom menus/actions/rendering disappear.

## Known gaps

- WhatsApp's native translation menus/settings have not been hidden or rewired. Their resource IDs/hooks are unverified. Disable native automatic translation when testing this replacement.
- This translates incoming messages when displayed, including history; it does not translate background notifications or messages in unopened chats.
- Messages over 4,000 characters are excluded. In-memory cache holds 200 results, up to 32 requests can be pending, and failures have a 60-second cooldown. There is no persistent translation cache.
- Runtime compatibility, layout, and all language/endpoint combinations need device testing. Menu hook failures log only the exception type, not message contents.
- Local compilation was blocked at Gradle download by network restrictions. The PR build workflow is intended to compile and produce a debug APK.
