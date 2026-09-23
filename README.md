> [!WARNING]
> **AI usage guidelines have been added!**
<!-- ---------- Header ---------- -->
<div align="center">
  <img width="200" height="200" src="fastlane/metadata/android/en-US/images/icon.png">
  <h1>Clock Pulse</h1>
  <p>Privacy focused clock app built with MD3.</p>

<!-- ---------- Badges ---------- -->
  <div align="center">
    <img alt="License" src="https://img.shields.io/github/license/you-apps/ClockPulse?color=c3e7ff&style=flat-square">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/you-apps/ClockPulse/total.svg?color=c3e7ff&style=flat-square">
    <img alt="Last commit" src="https://img.shields.io/github/last-commit/you-apps/ClockPulse?color=c3e7ff&style=flat-square">
    <img alt="Repo size" src="https://img.shields.io/github/repo-size/you-apps/ClockPulse?color=c3e7ff&style=flat-square">
    <img alt="Stars" src="https://img.shields.io/github/stars/you-apps/ClockPulse?color=c3e7ff&style=flat-square">
    <br>
  </div>
</div>

<!-- ---------- Description ---------- -->
## Features

- [x] Material Design 3 (You)
- [x] Dark and light theme
- [x] Integrated clock, alarm, timer and stopwatch
- [x] Convenient user interface
- [x] Written in Jetpack Compose 

## Google Calendar via OAuth

The Agenda tab can use one source at a time:

- **Local calendar** reads the device Calendar Provider after the user grants calendar permission.
- **Google Calendar via OAuth** lets the user connect multiple Google accounts. The Agenda lists each account as `Google Calendar` with its associated profile name and email.

These sources are deliberately exclusive. Selecting Google hides local-calendar events and controls. To return to Local, choose **Disconnect all** in the OAuth source; this revokes every connected account and removes all agenda events and alarms belonging to that source before the source selector is shown again. Removing one Google account only removes that account's events and alarms.

### Google Cloud setup for development

Create an Android OAuth client for every signing identity that will run the app:

| Variant | Android package name | Certificate fingerprint |
| --- | --- | --- |
| Debug | `app.clock.pulse.debug` | Debug keystore SHA-1 |
| Release | `app.clock.pulse` | Release signing-key SHA-1 |

Enable the Google Calendar API and add these scopes to the consent configuration:

- `https://www.googleapis.com/auth/calendar.readonly`
- `openid`
- `email`
- `profile`

While the OAuth consent screen is in Testing, add every Google account used for testing as a test user. The app uses direct Android OAuth and keeps access tokens only in memory. Do **not** add a downloaded `client_secret*.json`, a client secret, a refresh token, or a Web OAuth client credential to this repository or APK.

<!-- ---------- Download ---------- -->
## Download

<div align="center">
  <table border="0" cellpadding="0" cellspacing="0" style="border: none; border-collapse: collapse;">
    <tr style="border: none;">
      <td style="border: none; padding-right: 15px; vertical-align: middle;">
        <a href="https://f-droid.org/packages/com.bnyro.clock/">
          <img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80" />
        </a>
      </td>
      <td style="border: none; padding-left: 15px; padding-right: 15px; vertical-align: middle;">
        <a href="https://apt.izzysoft.de/fdroid/index/apk/com.bnyro.clock">
          <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="80" />
        </a>
      </td>
      <td style="border: none; padding-left: 15px; padding-right: 15px; vertical-align: middle;">
        <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/you-apps/ClockPulse">
          <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54" />
        </a>
      </td>
      <td style="border: none; padding-left: 15px; vertical-align: middle;">
        <a href="https://github.com/you-apps/ClockPulse/releases">
          <img src="ghbadge.png" alt="Get it on GitHub" height="80" />
        </a>
      </td>
    </tr>
  </table>
</div>

<!-- ---------- Contribution ---------- -->
## Feedback and contributions
***All contributions are very welcome!***
* Please read the [Contributing Rules about ai Usage](https://github.com/you-apps/ClockPulse/blob/7e9ec69e9aa650a42837257bd3a21f2db7104bb9/CONTRIBUTING).
* Feel free to join the [Matrix room](https://matrix.to/#/#you-apps:matrix.org) for discussions about the app.
* Bug reports and feature requests can be submitted [here](https://github.com/you-apps/ClockPulse/issues) (please make sure to fill out all the requested information properly!).
* If you are a developer and wish to contribute to the app, please **fork** the project and submit a [**pull request**](https://help.github.com/articles/about-pull-requests/).

## Translation

<a href="https://hosted.weblate.org/projects/you-apps/clock-pulse/">
  <img src="https://hosted.weblate.org/widgets/you-apps/-/clock-pulse/287x66-grey.png" alt="Translation status" />
</a>

## Credits

* Icon design by [M00NJ](https://github.com/M00NJ)
* Created by [You-Apps](https://github.com/you-apps)
* Maintained by [Elektron](https://github.com/Elektron123)

## License

Clock Pulse is licensed under the [**GNU General Public License**](https://www.gnu.org/licenses/gpl.html): You can use, study and share it as you want.
