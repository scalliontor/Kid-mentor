# P-Talk Signature - Request Quota Integration Guide

## Overview
P-Talk Signature now includes request counting for guest users (20 requests/day limit). Logged-in users have unlimited access.

## Changes Made

### 1. **QuotaManager.kt** (New File)
- Location: `app/src/main/java/com/ctslab/ptalk_signature/QuotaManager.kt`
- Manages guest request counting via SharedPreferences
- Provides quota status and usage percentage

### 2. **Integration Steps in MainActivity.kt**

#### Step 1: Initialize QuotaManager in onCreate()
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ... existing code ...
    
    // Add this line after setContentView
    QuotaManager.init(this)
}
```

#### Step 2: Add Quota Check Before Recording
Modify the start recording logic (around line 300-307):

**Before:**
```kotlin
if (!hasMicPermission()) {
    requestMicPermission()
    viewModel.statusText.value = "Cần cấp quyền micro để ghi âm"
    Toast.makeText(this, "Vui lòng cấp quyền micro để tiếp tục.", Toast.LENGTH_SHORT).show()
    return false
}
```

**After:**
```kotlin
if (!hasMicPermission()) {
    requestMicPermission()
    viewModel.statusText.value = "Cần cấp quyền micro để ghi âm"
    Toast.makeText(this, "Vui lòng cấp quyền micro để tiếp tục.", Toast.LENGTH_SHORT).show()
    return false
}

// Check quota for guest users
if (!QuotaManager.isLoggedIn() && QuotaManager.isQuotaExhausted()) {
    viewModel.onError("Bạn đã dùng hết lượt trò chuyện hôm nay (20 lượt). Hãy đăng nhập để tiếp tục.")
    return false
}
```

#### Step 3: Increment Request Count
Add this after successful recording:
```kotlin
// In startStreaming() or startLegacyMicCapture() after successful start
if (QuotaManager.incrementRequest()) {
    val status = QuotaManager.getQuotaStatus()
    if (!QuotaManager.isLoggedIn()) {
        viewModel.statusText.value = "Lượt: ${status.used}/${status.limit}"
    }
} else {
    viewModel.onError("Bạn đã dùng hết lượt!")
    stopRecording()
    return false
}
```

#### Step 4: Display Quota in UI (Optional)
Add to your layout or status bar:
```kotlin
// Show quota percentage for guest users
if (!QuotaManager.isLoggedIn()) {
    val percentage = QuotaManager.getUsagePercentage()
    binding.quotaProgressBar.progress = percentage
    val status = QuotaManager.getQuotaStatus()
    binding.tvQuotaText.text = "${status.used}/${status.limit}"
}
```

## API Reference

### QuotaManager Methods

```kotlin
// Initialize (call in onCreate)
QuotaManager.init(context)

// Set current user
QuotaManager.setCurrentUser(username)

// Check if user is logged in
QuotaManager.isLoggedIn(): Boolean

// Increment count and check limit
QuotaManager.incrementRequest(): Boolean  // returns true if allowed

// Get quota status
QuotaManager.getQuotaStatus(): QuotaStatus
  - status.used: Int (requests used today)
  - status.limit: Int (daily limit)
  - status.isUnlimited: Boolean

// Get usage as percentage (0-100)
QuotaManager.getUsagePercentage(): Int

// Check if quota exhausted
QuotaManager.isQuotaExhausted(): Boolean

// Reset quota (for testing)
QuotaManager.resetQuota()
```

## Guest Quota Logic

- **Daily Limit**: 20 requests per day
- **Date Reset**: Automatically resets at midnight (based on device date)
- **Storage**: SharedPreferences (ptalk_signature_quota)
- **Logged-in Users**: Unlimited (set via `setCurrentUser()`)

## Test Scenarios

### Test 1: Guest User Quota
1. Clear app data to reset quota
2. Make 20 requests - should all succeed
3. Request #21 - should fail with "quota exhausted" message

### Test 2: Quota Reset
1. Make some requests (e.g., 5)
2. Change device date forward by 1 day
3. Make another request - should succeed (quota reset)

### Test 3: Logged-in User
1. Call `QuotaManager.setCurrentUser("username")`
2. Make unlimited requests - all should succeed
3. No quota messages should appear

## Integration Checklist

- [ ] Add `QuotaManager.init(this)` in MainActivity.onCreate()
- [ ] Add quota exhaustion check before startRecording()
- [ ] Add `incrementRequest()` call after recording starts
- [ ] Add UI elements for quota display (optional)
- [ ] Test guest quota limit
- [ ] Test quota reset on date change
- [ ] Test logged-in user (unlimited)
- [ ] Test error messages

## Future Enhancements

1. **Server-side Sync**: Fetch user quota from backend API instead of local count
2. **Quota Upgrade**: Premium tier users get higher daily limits
3. **Daily Notifications**: Notify user when quota is 80%+ used
4. **Analytics**: Track quota usage patterns
