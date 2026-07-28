# BlueNet Project Rules

## Android Development Rules

### 1. BroadcastReceiver Registration (Android 14+ / API 34 Compatibility)
- **Constraint:** In Android 14 (API 34) and higher, all receivers registering for non-system broadcasts must explicitly declare their export flags.
- **Rule:** Never use plain `context.registerReceiver(receiver, filter)`. Always use the compatibility wrapper:
  ```kotlin
  ContextCompat.registerReceiver(
      context,
      receiver,
      filter,
      ContextCompat.RECEIVER_NOT_EXPORTED // or ContextCompat.RECEIVER_EXPORTED depending on usage
  )
  ```

### 2. Notification Permissions (Android 13+ / API 33 Compatibility)
- **Constraint:** Posting notifications requires `android.permission.POST_NOTIFICATIONS` at runtime when targeting API 33+.
- **Rule:** When calling `notificationManager.notify()`, always verify the permission has been granted first. Annotate the helper method with:
  ```kotlin
  @SuppressLint("MissingPermission", "NotificationPermission")
  ```

### 3. Material Design 3 Attribute Naming
- **Constraint:** Using incorrect or shortened Material theme attribute resource IDs (like `?attr/onPrimaryContainer` instead of `?attr/colorOnPrimaryContainer`) will fail resource linking at build time.
- **Rule:** Always use the full M3 attribute names (e.g., `?attr/colorOnPrimaryContainer`, `?attr/colorPrimaryContainer`, `?attr/colorOnSurfaceVariant`).

## Git and Workspace Management Rules

### 1. Project Transition Files (Local Only)
- **Constraint:** Planning or session transition files (e.g. `next.md`) should remain local to the workspace to avoid polluting remote version control.
- **Rule:** When creating session notes or files requested by the user for continuation purposes, immediately add the filename to `.gitignore` and untrack it if already added using:
  ```bash
  git rm --cached <filename>
  ```
