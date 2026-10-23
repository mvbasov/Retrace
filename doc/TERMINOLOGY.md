# Retrace terminology

This table is the controlled vocabulary of Retrace. It binds the README, the documents, the user interface text, the code comments and the commit messages.

Use one name for one thing. If a term is not in the table, use the plain word. Add a new term to this table before you use it in two places.

| Term | Meaning | Do not use |
| --- | --- | --- |
| track | A list of points with a time each. Retrace saves one track as one GPX file. | path, log, recording |
| log file | The file `current.log` that holds the points of the current track during a trip. | temporary file, journal |
| point | One stored position of a track: latitude, longitude, time and accuracy. | sample, waypoint |
| fix | One position that the GPS receiver gives. A fix becomes a point after the filter. | reading, location update |
| trip | The movement from the start to the finish. One trip gives one track. | journey, ride, session |
| route | The line on the ground that a track follows. | course, way |
| reference track | The saved track that Retrace compares with the current track. | baseline, master track, template |
| current track | The track that Retrace records now. | live track, actual track |
| profile | The settings for one type of movement: walking, running, cycling or driving. | mode, activity type |
| start, finish | The first point and the last point of the reference track. | origin, goal, destination |
| start circle, finish circle | The circle around the start and the finish. Its radius is the radius of the profile plus twice the accuracy of the fix. | start zone, geofence |
| leg | A part of a route in one direction. A route out and back has two legs. | branch, section |
| join | To find the route away from its start, at the first fixes near it. | snap, attach |
| drift | The slow part of the GPS error. It changes over minutes. | bias, offset |
| distance along the route | The distance from the start to your projection on the reference track. The code calls it `s`. | progress, odometer |
| cross-track distance | The distance from your position to the nearest part of the route. | deviation, offset, error |
| on route, off route | The cross-track distance is below or above the limit of the profile. | lost, deviated |
| ahead, behind | You are earlier or later than the reference track at the same distance along the route. Use these words for time only. | faster, slower (for time) |
| faster, slower | Your speed is higher or lower than the speed of the reference track at the same place. Use these words for speed only. | ahead, behind (for speed) |
| ghost | The position on the reference track at your elapsed time. | shadow, pacer |
| trip screen | The screen with the comparison and the map during a trip. | run screen, live view, dashboard |
| elapsed time | The time since the start of the trip. | duration, run time |
| select | To choose the reference track, by Retrace or by you. | pick, choose |
| tile | One square image of the map from the tile server. | map piece, image |
| tile server | The web server that sends the tiles. | map server, tile provider |
| tile cache | The tiles that Retrace keeps in memory and on the disk. | offline map, store |
| push | To operate a button on the screen. | click, press, tap, hit |
| long push | To push and hold an item of a list. | long press, long click |
