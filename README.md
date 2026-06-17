# MOAF Campaign Index

A Java 21 desktop campaign index for **Memories of a Few**. The app starts a private local server and opens in the default browser.

## Players do not need GitHub

GitHub is only the hidden hosting service used by the GM. Players do **not** need:

- a GitHub account
- the GitHub desktop program
- access to your repository settings
- a publishing token
- any knowledge of how GitHub works

They launch the MOAF app normally. It automatically downloads the latest unlocked campaign data from a public read-only address. The player-facing button is simply **Check for Updates**.

## Start the app

### Windows
Double-click `run-moaf.bat` or run:

```text
java -jar MOAF-Campaign-App.jar
```

Java 21 or newer is required for the `.jar`. The included GitHub Actions workflow can later create a Windows installer with Java bundled.

### Initial admin PIN

```text
2089
```

Change it under **Admin Login → Manage Campaign → Campaign Settings**.

## GM editing

Admin mode can:

- Add, rename, reorder, hide, and delete tabs
- Add or edit NPCs, factions, maps, notes, evidence, and custom records
- Upload images or use image URLs
- Lock or unlock records
- Export and import the complete local master backup
- Publish only unlocked records

The complete GM version is stored locally in `campaign-master.json`. Never give that file to players.

## One-time GitHub setup for the GM

1. Create a new **public** GitHub repository, such as `moaf-player-index`.
2. Create a fine-grained personal access token restricted to that repository.
3. Give the token **Contents: Read and write** permission.
4. In the app, open **Admin Login → Manage Campaign**.
5. Enter your repository owner, repository name, branch, player-data path, and token.
6. Select **Test Connection** and then **Publish Player View**.

After that, place a player-safe `github-config.json` beside the player app:

```json
{
  "owner": "YOUR_GITHUB_USERNAME",
  "repo": "moaf-player-index",
  "branch": "main",
  "path": "campaign.json",
  "token": "",
  "autoSync": true
}
```

This file is only an update address. It contains no password and grants no editing access. Players can open the public repository in a browser if they deliberately investigate it, but the repository contains only information you have already unlocked.

## Distribution rule

Do not copy your entire GM folder to the players. In particular, never send:

- `campaign-master.json`
- your GM `github-config.json` containing a token
- unrevealed images or legacy HTML pages containing secrets

Once your repository is created, make a clean player folder containing the app, the token-free update configuration, and only player-safe assets.

## Privacy model

Locked records are removed before `campaign.json` is published. A public repository cannot conceal files already uploaded, so secret maps, images, and unrevealed legacy HTML must remain on the GM computer until revealed.

## Files

- `MOAF-Campaign-App.jar` — runnable app
- `src/MoafCampaignApp.java` — single-file Java source
- `campaign-master.json` — local GM data, created on first launch
- `github-config.json` — online publishing/update settings
- `content/legacy/` — original HTML indexes
- `.github/workflows/build-windows.yml` — Windows installer workflow
