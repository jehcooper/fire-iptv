# Sideline: IPTV + live sports for Fire TV

A personal IPTV player for Fire TV (Fire OS, Android-based models only). It doesn't work on Vega OS sticks.

- Several Xtream Codes accounts in one place: choose for each whether it supplies live channels, movies & series, or both.
- Sports home screen: favourite leagues and teams, live and upcoming games, final scores.
- Score pop-ups while you watch, for games you follow (Menu ≡ on a game) and, optionally, any game your teams play.
- "Take me to the game": finds the channel showing a game from channel names, ESPN's broadcaster list and your TV guide. Best effort.
- Auto-failover to a backup copy of a channel when a stream dies or stalls.
- ◀ in the player jumps back to the previous channel. ≡ watches the current show from the start (catch-up providers only).
- Movies and episodes resume where you left off. Spoiler-free mode hides scores.

## Install on the Fire TV
1. Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → turn on for **Downloader**.
   (If Developer options is hidden: Settings → My Fire TV → About → click the device name 7 times.)
2. Install the free **Downloader** app from the Amazon Appstore.
3. In Downloader, enter the APK link from this repo's latest release (see below), install, then open **Sideline**.

Every push to `main` builds a new `Sideline.apk` under **Releases**. Updates install over the old version and keep your settings.

## Notes
- Scores use ESPN's public but undocumented JSON feed. It may change without notice. Cricket uses a different ESPN feed and is beta.
- Your IPTV logins are stored only on the Fire TV, not in this repository.
