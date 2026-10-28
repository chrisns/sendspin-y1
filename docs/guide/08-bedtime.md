---
chapter: 8
title: Bedtime
---

# 8. Bedtime

*Sleep well*

## Narration

> Every night there is a bedtime window, and a little time to listen. When your time is used up, the iPod fades out and says goodnight.
>
> That is not a punishment. It is to help you sleep. If a grown-up says it is fine, they can unlock it for a while with their special code. They can see and change your bedtime from their phone, too.

## On screen

- ![When tonight's listening is used up, it says goodnight. Only a grown-up's code (or Wi-Fi settings) works.](../screenshots/33-bedtime-lock.png) <!-- shot:bedtime-lock -->
- ![Bedtime window and allowance, click sound, names, Find My, hold time, the 'if found' contact and more. Change one and the player has it within about 30 seconds.](../screenshots/35-ha-device-config.png) <!-- shot:ha-device-config -->

## For grown-ups

The allowance, window and counter live in Home Assistant (device page: *Bedtime starts / ends / allowance / minutes used*, optionally linked to existing helpers so a bedroom speaker shares the same budget). Minutes are added with a sequence number so a resend is never counted twice. A grown-up code (5-digit TOTP, one use each, from 1Password) has two uses, and one code does only one of them. Typed on the goodnight screen it lifts the limit for 8 hours and tells a parent (event and notification). Typed in Settings it only opens the protected settings for 10 minutes (less if the screen goes off), with no notification and no change to the limit. Five wrong codes in a row lock code entry for a minute, doubling each time.
