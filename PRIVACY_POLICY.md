# 반납요정 개인정보처리방침 / Return Fairy Privacy Policy

최종 수정일 / Last updated: 2026-10-05

## 한국어

반납요정(이하 "앱")은 빌린 책의 반납일을 관리하는 도구입니다. 본 방침은 앱이 정보를 어떻게 처리하는지 설명합니다.

### 1. 수집·저장하는 정보

앱은 다음 정보를 **이용자의 기기에만** 저장합니다.

- **책 기록**: 이용자가 직접 입력한 제목, 대출일/반납일, 빌린 사람 등
- **도서관 계정 정보(선택)**: 송파도서관 연동 기능 사용 시 입력한 회원번호와 비밀번호. 로그인 시 HTTPS로 송파도서관 서버(splib.or.kr)에 직접 전송됩니다.

앱은 분석 도구·광고 SDK·추적 기술을 사용하지 않으며, 개발자가 운영하는 서버가 없어 어떤 정보도 개발자에게 전송되지 않습니다.

**새 버전 확인**: 설정에서 켜 두면(기본값) 앱을 열 때 하루 한 번까지 GitHub(api.github.com)에 이 앱의 최신 공개 릴리스 버전을 묻습니다. 이때 책 기록·계정 등 이용자 정보는 보내지 않습니다. 다른 웹 요청과 마찬가지로 GitHub는 접속 IP 주소 등 일반적인 접속 기록을 받을 수 있습니다. 설정 → 앱 → 새 버전 확인에서 끌 수 있습니다.

### 2. 정보의 보관 및 보호

- 도서관 비밀번호는 Android Keystore(AES-GCM)로 암호화되어 앱 전용 저장소에 보관됩니다.
- 암호화 키는 기기 외부로 내보낼 수 없으며, 다른 앱은 접근할 수 없습니다.

### 3. 권한

- **알림**: 반납일 알림 발송에만 사용됩니다.
- **카메라**: 대출 영수증·책 표지를 촬영해 글자를 읽는 데만 사용됩니다. 문자 인식은 기기 안에서 처리되며, 촬영한 사진은 임시 저장 후 다음 촬영 시 대체되고 외부로 전송되지 않습니다. 한국어 문자 인식 모델은 처음 사용할 때 Google Play 서비스가 내려받습니다(사진은 보내지 않음).

실험실 기능인 AI 사진 인식을 사용하는 경우, 이용자의 요청으로 내려받거나 직접 가져온 AI 모델 파일이 이 앱의 저장 공간(앱 삭제 시 함께 삭제)에 보관되며 사진 인식은 전부 기기 안에서 처리됩니다. 설치된 모델은 규칙으로 인식되지 않는 자연어 반납일 표현을 해석할 때도 사용될 수 있습니다. 모델 다운로드는 이용자가 버튼을 눌렀을 때만 공개 저장소(Hugging Face)에서 이루어지며, 사진·반납일 표현·인식 결과·개인정보는 어떤 것도 전송되지 않습니다.

### 4. 정보의 삭제

- 앱 내에서 책 기록과 계정을 개별 삭제할 수 있습니다.
- 앱을 삭제하면 저장된 모든 정보가 함께 삭제됩니다.

### 5. 아동의 개인정보

앱은 아동을 대상으로 하지 않으며, 아동의 개인정보를 의도적으로 수집하지 않습니다.

### 6. 문의

- GitHub: https://github.com/pchuri/return-fairy/issues

## English

Return Fairy (the "app") is a tool for tracking due dates of borrowed books. This policy explains how the app handles information.

### 1. Information stored

The app stores the following **only on your device**:

- **Book records**: titles, loan/due dates, and borrower tags you enter
- **Library credentials (optional)**: if you use the Songpa Library sync feature, the member ID and password you enter are sent directly to the library server (splib.or.kr) over HTTPS for login only.

The app contains no analytics, no ads, and no tracking. There is no developer-operated server, so no information is ever transmitted to the developer.

**Update check**: when enabled in Settings (the default), the app asks GitHub (api.github.com), at most once a day when you open it, for the latest public release version of this app. No book records, accounts, or other personal data are sent. Like any web request, GitHub may receive standard connection data such as your IP address. You can turn this off under Settings → App → Check for updates.

### 2. Protection

- Library passwords are encrypted with the Android Keystore (AES-GCM) and stored in app-private storage.
- The encryption key cannot leave the device and is inaccessible to other apps.

### 3. Permissions

- **Notifications**: used solely for due-date reminders.
- **Camera**: used only to photograph checkout receipts and book covers for text
  recognition. Recognition runs on-device; the photo is kept in a temporary file
  that the next scan overwrites, and is never uploaded. The Korean text-recognition
  model is downloaded by Google Play services on first use (no photos are sent).

If you enable the experimental AI photo recognition (Labs), an AI model file —
downloaded from a public repository (Hugging Face) only when you request it, or
imported by you — is stored in this app's storage (deleted with the app), and all recognition runs
entirely on the device. The installed model may also interpret natural-language
due-date phrases that the built-in rules do not recognize. Photos, due-date
phrases, and recognition results are never uploaded.

### 4. Deletion

- Book records and accounts can be deleted individually in the app.
- Uninstalling the app deletes all stored data.

### 5. Children

The app is not directed at children and does not knowingly collect children's personal information.

### 6. Contact

- GitHub: https://github.com/pchuri/return-fairy/issues
