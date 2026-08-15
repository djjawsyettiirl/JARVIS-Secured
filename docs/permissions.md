# JARVIS Permission Model

JARVIS has two different permission layers. They should not be treated as the same thing.

## Android OS permissions

Android controls access to microphone, camera, notifications, and nearby Bluetooth devices. JARVIS should request these at first launch in a clear onboarding screen, with a description of why each capability is needed. The user can deny any capability and grant it later in Android Settings.

JARVIS must never attempt to bypass Android's runtime permission system.

## Windows host permissions

Windows also does not provide a legitimate API for silently granting every privacy permission to an application. The installer can request administrator approval and configure the JARVIS service/firewall rule, but microphone/camera/privacy controls remain governed by Windows and user consent.

The installer should therefore:

- request UAC elevation once;
- create the JARVIS application data directory;
- create a private-network-only firewall rule for the local gateway if needed;
- install/start the host service;
- open the local control panel;
- never disable Windows security controls or grant unrestricted system privileges.

## Device capability scopes

These are JARVIS-level permissions and are independent of OS permissions:

- `chat` — send/receive AI requests
- `pc_status` — read basic host status
- `notifications` — send JARVIS notifications to the device
- `microphone` — request/use microphone through an approved host action
- `camera` — request/use camera through an approved host action
- `files_read` — read explicitly selected files
- `files_write` — write explicitly selected files
- `pc_control` — execute approved PC automation actions; this should require explicit confirmation for destructive actions
- `google_gmail` — read Gmail summaries through the Google account connected on the Windows host
- `google_calendar` — read and manage Calendar events through the connected Google account
- `maps` — open Google Maps searches and directions
- `alarms` — create and announce local JARVIS alarms and reminders
- `messaging` — exchange messages through the authenticated Windows home client
- `location_share` — fetch and share the phone's location only after an explicit request
- `software_updates` — check and download a privately staged Android update from the paired host
- `admin` — reserved for the host owner; never granted automatically to a newly paired phone

Default newly paired devices should start with the least privilege needed for basic chat and status. The Windows control panel will become the place where the owner grants/revokes scopes.
