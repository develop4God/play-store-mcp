# Play Store MCP Server

> **Provenance:** this is a local, modified copy of [devexpert-io/play-store-mcp](https://github.com/devexpert-io/play-store-mcp) (MIT, © 2025 DevExpert), taken at commit `b36fad39b4b636fb1948dc86372e03458661b3d6` after a manual source audit. It has no upstream link. The original `LICENSE` is kept unchanged. Changes from upstream are listed in `CHANGES.md`.

An MCP (Model Context Protocol) server that enables interaction with Google Play Console to deploy and manage Android applications.

## Safety model

- Writes are limited to testing tracks: `deploy_app` accepts `internal` and `alpha`; `promote_release` only `internal` → `alpha`. `production` and `beta` are rejected, and the rule is enforced in the request types, not only in the tool schema.
- Every write is a **dry run** unless `confirm=true` is passed. A dry run validates input only and never contacts Google.
- The version code is read from the uploaded file, not supplied by the caller.
- Required arguments fail with an error; there are no silent defaults.
- Existing drafts, staged and halted releases on a track are kept; a new completed release supersedes the previous completed one.

## What is this?

This MCP server provides tools to:
- Upload an AAB/APK to a testing track
- Promote a completed release from internal to alpha
- Query the status of existing releases

## Available Tools

### 1. `deploy_app`
Uploads a new version to a testing track.

**Parameters:**
- `packageName` (string): Application id (e.g., com.example.myapp)
- `track` (string): `internal` or `alpha`
- `apkPath` (string): Absolute path to the `.aab` or `.apk` file
- `releaseNotes` (object, optional): Language tag to text, e.g. `{"en-US": "Fixes", "es-ES": "Correcciones"}`
- `confirm` (boolean, optional): Must be `true` to upload; default `false` (dry run)

### 2. `promote_release`
Promotes a completed release from `internal` to `alpha`.

**Parameters:**
- `packageName` (string): Application id
- `fromTrack` (string): `internal`
- `toTrack` (string): `alpha`
- `versionCode` (integer): Version code of the release to promote
- `confirm` (boolean, optional): Must be `true` to promote; default `false` (dry run)

### 3. `get_releases`
Lists the releases on every track (read-only).

**Parameters:**
- `packageName` (string): Application id (e.g., com.example.myapp)

## Setup

### 1. Google Cloud and Play Console Setup

#### Step 1: Create a Google Cloud Console project
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select an existing one
3. Enable the Google Play Android Developer API:
   - Go to "APIs & Services" → "Library"
   - Search for "Google Play Android Developer API"
   - Click "Enable"

#### Step 2: Create a service account
1. In Google Cloud Console, go to "IAM & Admin" → "Service Accounts"
2. Click "Create Service Account"
3. Fill in the details:
   - **Name**: `play-store-mcp`
   - **Description**: `Service account for Play Store MCP operations`
4. Click "Create and Continue"
5. In "Grant this service account access to project", you don't need to assign specific roles
6. Click "Done"

#### Step 3: Generate the service account key
1. In the service accounts list, find the one you just created
2. Click on the service account email
3. Go to the "Keys" tab
4. Click "Add Key" → "Create new key"
5. Select "JSON" as the key type
6. Click "Create"
7. A JSON file will download - this is your `service-account-key.json`

#### Step 4: Configure permissions in Google Play Console
1. Go to [Google Play Console](https://play.google.com/console)
2. Select your developer account
3. Go to "Users and Permssions"
4. Click "Invite new users"
5. Add the email of the service account
6. Click "Invite User"
8. Configure the necessary permissions:
   - **App permissions**: Select the apps it can manage
   - **Account permissions**: Grant "View app information" and the permissions aunder "Versions"

More permissions may be required when new features are released.

### 2. MCP Client Configuration

Add the following configuration to your MCP configuration file:

```json
{
  "mcpServers": {
    "play-store-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/play-store-mcp-all.jar"
      ],
      "env": {
        "PLAY_STORE_SERVICE_ACCOUNT_KEY_PATH": "/path/to/service-account-key.json"
      }
    }
  }
}
```

### Environment Variables

- `PLAY_STORE_SERVICE_ACCOUNT_KEY_PATH`: Path to the service account JSON file (required). The file must not be readable by group or others (`chmod 600`); the server refuses to start otherwise.

## Building

To build the project:

```bash
./gradlew clean build -x test
```

This will generate the JAR file at `build/libs/play-store-mcp-all.jar`.

You can also download the `jar` from the latest [release](https://github.com/devexpert-io/play-store-mcp/releases).

## Usage

Once configured, you can use the tools from any compatible MCP client.

Some samples of prompts are:

If you want to get the current releases of the App:

> Let me know what versions of com.example.myapp are released in the Play Store.

To release a new version of the App to the alpha channel, you can say:

> Upgrade the version of the App, build the AAB, and upload it to the alpha channel.

Every write first answers as a dry run; confirm it to execute.

## Requirements

- Java 8 or higher
- Google Play Console developer account
- Google Cloud project with Play Developer API enabled
- Applications already published in Play Console (at least as drafts)

## Limitations

- Only supports APK and AAB files
- Requires applications to be already configured in Play Console
- Service account permissions must be configured manually in Play Console

## Troubleshooting

### Error: "Service account key not found"
Verify that the path in `PLAY_STORE_SERVICE_ACCOUNT_KEY_PATH` is correct and the file exists.

### Error: "The caller does not have permission"
Ensure the service account is properly configured in Google Play Console with the necessary permissions.

### Error: "Package name not found"
Verify that the package name is correct and the application is configured in Play Console.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.