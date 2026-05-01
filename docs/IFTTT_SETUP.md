# IFTTT setup for the automated update Routine

One-time configuration for the Android-device notification step in the [automated update playbook](AUTOMATED_UPDATE_PLAYBOOK.md). The playbook's daily Routine fires this applet whenever it opens a PR or an issue.

If you skip this section, the playbook still works — GitHub's built-in PR/issue email becomes the notification path. The IFTTT layer is a convenience, not a dependency.

---

## What you'll end up with

A push notification on your Android phone (via the IFTTT app) every time the Routine has work that needs your attention. The notification message contains the PR or issue URL plus a one-line summary of what's needed.

---

## Prerequisites

- An IFTTT account ([ifttt.com](https://ifttt.com)) — the free tier is sufficient for low-volume Routine notifications.
- The **IFTTT app** installed on the Android device you want to receive notifications on, signed in with the same account. The IFTTT "Notifications" service delivers via this app — no SMS provider needed.
- The Claude.ai IFTTT integration enabled on your Claude account so the Routine agent can trigger the applet.

---

## Setup

### 1. Create the applet

1. Go to <https://ifttt.com/create>.

2. **If This** (trigger) — click **Add**. Search for and choose **Webhooks** (officially "Maker Webhooks"). Pick the trigger **Receive a web request with a JSON payload**.
   - **Event Name:** `kanetik_pbl_update`
   - Click **Create trigger**.

   The Webhooks trigger is what the Claude.ai IFTTT integration fires under the hood. The `kanetik_pbl_update` event name is the unique key the integration matches against.

3. **Then That** (action) — click **Add**. Search for and choose **Notifications** (the IFTTT-built-in "Notifications" service). Pick the action **Send a notification from the IFTTT app**.
   - **Message:** `{{Value1}}`
   - Click **Create action**.

   The `{{Value1}}` placeholder receives the message text the playbook sends. Keep it as `{{Value1}}` exactly — the playbook hands the full URL + summary in that field.

4. Click **Continue** through the rest of the wizard. On the final review page:
   - **Title:** `Kanetik PBL Update Notification` (case-sensitive — the playbook looks up by this exact name)
   - Confirm everything else.
   - Click **Finish**.

5. Make sure the applet shows as **Connected** on your "My Applets" page.

### 2. Verify on your phone

1. Make sure the IFTTT app is installed and signed in on your Android device.
2. Make sure notifications from the IFTTT app are enabled in Android Settings → Apps → IFTTT → Notifications.

### 3. Test the applet manually

From [ifttt.com → My Applets → Kanetik PBL Update Notification](https://ifttt.com/my_applets) → **Settings** → **View activity log**, you can manually fire the trigger to confirm the notification reaches your phone.

You can also send a test request via curl using your Webhooks key (find it at <https://ifttt.com/maker_webhooks>):
```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"value1":"Test from setup — if you see this on your phone, the applet works."}' \
  https://maker.ifttt.com/trigger/kanetik_pbl_update/with/key/<YOUR_WEBHOOKS_KEY>
```

If the test notification arrives within ~30 seconds, you're set.

### 4. Confirm the playbook can find the applet

The agent's lookup uses `mcp__claude_ai_IFTTT__my_applets` and matches the applet name `Kanetik PBL Update Notification` exactly. After saving the applet:
- If you rename it, update the playbook's section 10 AND the Routine prompt's section 11 to match.
- If you create multiple notification applets, the playbook only fires the one with this exact name.

---

## Customization

### Different message format

Edit the applet's **Notification message** field. The available variables are:
- `{{Value1}}` — the full message body (URL + summary) that the playbook sends
- `{{OccurredAt}}` — IFTTT-provided timestamp (formatted)
- `{{EventName}}` — `kanetik_pbl_update`

A more verbose format you might prefer:
```
Kanetik billing — {{OccurredAt}}
{{Value1}}
```

### Different delivery channel

The IFTTT "Notifications" service is the simplest. Other options:
- **SMS via Twilio** — IFTTT has a Twilio service. Real SMS, but Twilio costs ~$0.0075 per message.
- **Email** — IFTTT's Email service. Slower than push, but useful as an audit log.
- **Slack / Discord** — IFTTT has both. Useful if you'd rather see notifications in a chat channel.
- **Multiple channels** — IFTTT Pro lets you stack actions in one applet (Notification + email, for example). Free tier is one action per applet.

If you change the action, the trigger and applet name stay the same — the playbook only cares about firing the trigger.

### Different applet name

If `Kanetik PBL Update Notification` doesn't fit your naming convention:
1. Rename the applet in IFTTT.
2. Edit `docs/AUTOMATED_UPDATE_PLAYBOOK.md` section 10 and section 11 (the Routine prompt) to match.
3. Update both places — the rule and the prompt — to keep them in sync.

---

## Disabling

To temporarily silence notifications without breaking the playbook:
- IFTTT → My Applets → `Kanetik PBL Update Notification` → toggle **Connect / Disconnect**.

The playbook's `mcp__claude_ai_IFTTT__my_applets` lookup still finds the applet (it's just disconnected, not deleted). The agent's `run_action` call no-ops; the playbook logs the failure as a comment on the PR/issue and proceeds. The PR/issue itself is unaffected.

To delete the applet entirely, the lookup-by-name returns nothing and the agent falls back to GitHub email.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Test notification doesn't arrive on phone | IFTTT app isn't installed or notifications are blocked at the Android OS level. Check Settings → Apps → IFTTT → Notifications. |
| Test arrives but Routine notifications don't | The Routine agent isn't finding the applet by name. Verify the exact applet name (case-sensitive) and that it's connected. |
| Multiple notifications for one PR | Either the Routine ran more than once (check the Routine's run history) or the playbook's notify step is being called multiple times. The playbook calls notify exactly once per PR/issue creation; if you see duplicates, the Routine schedule may be too aggressive. |
| Notification text is `Value1` literal | The action template wasn't set to `{{Value1}}` — fix the applet's notification-message field. |
| `mcp__claude_ai_IFTTT__my_applets` returns empty | The Claude.ai IFTTT integration isn't connected on the Routine's account. Re-authenticate the integration. |
