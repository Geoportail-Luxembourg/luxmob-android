# Development

- Get android studio from https://developer.android.com/studio/
- Run on real device or emulator using https://developer.android.com/training/basics/firstapp/running-app

# Internal documentation
The app runs an internal http server with the following tasks:
- serve static files for map styles (with some rewriting adapt the URLs to the local configuration)
- serve (vector) tiles for different map layers (roads, topo, contours, etc.)
- provide versions of the currently installed offline data
- provide methods to update or delete offline data

## internal server
the internal server listens on http://localhost:8766
An exception is created in the app security policy to allow non-https traffic with this internal server.

## update of offline tiles
there are three main entrypoints for management of offline data:

### GET /check
retrieve the local state of offline data as a json structure.

Available updates are retrieved from versions.json (currently a static local file, eventually on remote server)
All avaliable versions and download URLs are read from this file
Then they are compared to the local versions.
Comprehensive information is returned for each available data package:
- Status (download in progress or complete)
- Current file size (for progress calculation)
- Current version on local file system
- Available version on remote server

### POST /update?map=<mapname>
launch the download of the offline data package named "mapname". The name must correspond to the top level categories in versions.json
The server answers with 202 and does the download starts silently in the background.
The server responds with a 404 if the download of the requested data package is already in progress and does not launch another download until the original request is complete

### POST /delete?map=<mapname>
delete the offline data for the given package name. (same naming as for update)

## detailed steps of download:
With the POST /update service, the download of one remote data package is launched and the process goes through the steps below:
- The data is downloaded to a temporary location until it is complete
- If the download is successful, the old files (if present) are deleted
- Then the new data os moved to the correct destination
- The list of downloaded files is kept as metadata for later cleanup
