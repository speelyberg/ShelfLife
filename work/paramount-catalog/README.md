# Paramount Feature Presentation release catalog

`android/app/src/main/assets/paramount_releases.tsv` contains `year|normalized title` keys. `Bumpers.forMovie` checks it before Plex's production-studio field. An explicit Plex studio containing "Paramount" is also accepted. Broad studio-family guesses (DreamWorks, Skydance, Nickelodeon, etc.) were removed because those companies also made films released by other distributors.

Source audit: Paramount's [movie library](https://www.paramount.com/movies) (all 92 pages, fetched 27 October 2026), plus the twelve linked decade lists at [Lists of Paramount Pictures films](https://en.wikipedia.org/wiki/Lists_of_Paramount_Pictures_films) (fetched 27 October 2026). Wikipedia entries marked "studio credit only" or "associate credit only" were excluded; they do not establish a Paramount theatrical release. Titles from Paramount's current library that match a film in a Plex catalog were paired with that film's year so remakes do not collide. The resulting file has 3,199 unique keys.


This is a snapshot. If newly added films are absent from the snapshot and Plex names a different production company, add verified release keys to the TSV.
