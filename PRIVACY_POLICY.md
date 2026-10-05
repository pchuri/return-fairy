# 반납요정 개인정보처리방침 / Return Fairy Privacy Policy

최종 수정일 / Last updated: 2026-10-06

## 한국어

반납요정(이하 "앱")은 송파구립도서관(splib.or.kr) 계정의 대출·상호대차·예약 현황을 보여 주는 도구입니다. 본 방침은 앱이 정보를 어떻게 처리하는지 설명합니다.

### 1. 저장하는 정보

앱은 다음 정보를 **이용자의 기기에만** 저장합니다.

- **도서관 계정**: 이용자가 입력한 아이디·비밀번호와 표시 이름. 조회할 때 HTTPS로 송파구립도서관 서버(splib.or.kr)에 직접 로그인하는 데만 쓰입니다.
- **마지막 조회 결과**: 책 제목, 반납일, 도서관, 예약 순번 등. 앱을 열 때 바로 보여 주고 인터넷이 안 될 때 확인하기 위해 앱 전용 저장소에 둡니다. 결과를 계정과 맞추려고 계정 아이디를 함께 저장하며, 비밀번호는 들어가지 않습니다.
- **설정**: 알림 시각, 새 버전 확인 여부.

앱은 분석 도구·광고 SDK·추적 기술을 사용하지 않으며, 개발자가 운영하는 서버가 없어 어떤 정보도 개발자에게 전송되지 않습니다.

**새 버전 확인**: 설정에서 켜 두면(기본값) 앱을 열 때 하루 한 번까지 GitHub(api.github.com)에 이 앱의 최신 공개 릴리스 버전을 묻습니다. 이때 책 기록·계정 등 이용자 정보는 보내지 않습니다. 다른 웹 요청과 마찬가지로 GitHub는 접속 IP 주소 등 일반적인 접속 기록을 받을 수 있습니다. 설정 → 앱 → 새 버전 확인에서 끌 수 있습니다.

### 2. 정보의 보관 및 보호

- 도서관 비밀번호는 Android Keystore(AES-GCM)로 암호화되어 앱 전용 저장소에 보관됩니다.
- 암호화 키는 기기 외부로 내보낼 수 없으며, 다른 앱은 접근할 수 없습니다.

### 3. 권한

- **인터넷**: 송파구립도서관 조회와 새 버전 확인에만 사용됩니다.
- **알림**: 반납·찾아올 책 알림에만 사용됩니다.
- **백그라운드 조회**: 매일 정한 시각(설정 → 알림)에 앱이 백그라운드에서 도서관을 조회해 마지막 조회 결과를 갱신하고, 알림이 허용되어 있으면 알립니다. 이를 위해 Android WorkManager가 네트워크 상태 확인·재부팅 후 예약 유지 등 필요한 시스템 권한을 함께 사용합니다.

### 4. 정보의 삭제

- 설정에서 계정을 삭제할 수 있습니다. 계정을 모두 삭제하면 마지막 조회 결과도 함께 지워집니다.
- 앱을 삭제하면 저장된 모든 정보가 함께 삭제됩니다.

### 5. 아동의 개인정보

앱은 아동을 대상으로 하지 않으며, 아동의 개인정보를 의도적으로 수집하지 않습니다.

### 6. 문의

- GitHub: https://github.com/pchuri/return-fairy/issues

## English

Return Fairy (the "app") shows loans, interlibrary requests and reservations for Songpa Public Library (splib.or.kr) accounts. This policy explains how the app handles information.

### 1. Information stored

The app stores the following **only on your device**:

- **Library accounts**: the ID, password and display name you enter. They are used only to sign in directly to the library server (splib.or.kr) over HTTPS.
- **Last lookup result**: book titles, due dates, libraries, reservation ranks and similar, kept in app-private storage so the app opens instantly and works offline. The account ID is stored with it to match results to accounts; passwords are not.
- **Settings**: reminder time and the update-check switch.

The app contains no analytics, no ads, and no tracking. There is no developer-operated server, so no information is ever transmitted to the developer.

**Update check**: when enabled in Settings (the default), the app asks GitHub (api.github.com), at most once a day when you open it, for the latest public release version of this app. No book records, accounts, or other personal data are sent. Like any web request, GitHub may receive standard connection data such as your IP address. You can turn this off under Settings → App → Check for updates.

### 2. Protection

- Library passwords are encrypted with the Android Keystore (AES-GCM) and stored in app-private storage.
- The encryption key cannot leave the device and is inaccessible to other apps.

### 3. Permissions

- **Internet**: used only for library lookups and the update check.
- **Notifications**: used only for due-date and pickup reminders.
- **Background lookup**: every day at the chosen time (Settings → Notifications) the app looks up your accounts in the background to refresh the last result, and notifies you if notifications are allowed. Android WorkManager uses the system permissions it needs for this, such as checking network state and keeping the schedule after a reboot.

### 4. Deletion

- Accounts can be removed in Settings. Removing all accounts also deletes the last lookup result.
- Uninstalling the app deletes all stored data.

### 5. Children

The app is not directed at children and does not knowingly collect children's personal information.

### 6. Contact

- GitHub: https://github.com/pchuri/return-fairy/issues
