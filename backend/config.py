import os

# Get a free API key at https://www.themoviedb.org/settings/api and pass it
# via the TMDB_API_KEY environment variable. Without it, the guide still works
# but has no posters/backdrops.
TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "")

# Directory containing your XMLTV .xml files (every .xml in it is parsed)
DATA_DIR = os.environ.get("HOMELAB_DATA_DIR", "data")

if not os.path.exists(DATA_DIR):
    os.makedirs(DATA_DIR)
