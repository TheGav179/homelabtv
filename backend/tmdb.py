import httpx
from config import TMDB_API_KEY
import urllib.parse
from database import get_cached_tmdb, set_cached_tmdb

TMDB_BASE_URL = "https://api.themoviedb.org/3"
IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"
BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/w1280"

async def search_tmdb(query: str):
    """Fuzzy-match a TV show or Movie title against TMDB and return rich metadata and images."""
    if not TMDB_API_KEY or TMDB_API_KEY == "":
        return None

    # First, try searching for a TV show (since this is Live TV, it's mostly shows)
    encoded_query = urllib.parse.quote(query)
    search_url = f"{TMDB_BASE_URL}/search/tv?api_key={TMDB_API_KEY}&query={encoded_query}&page=1"
    
    try:
        async with httpx.AsyncClient() as client:
            response = await client.get(search_url)
            if response.status_code == 200:
                data = response.json()
                if data.get("results") and len(data["results"]) > 0:
                    best_match = data["results"][0]
                    return {
                        "tmdb_id": best_match.get("id"),
                        "title": best_match.get("name"),
                        "overview": best_match.get("overview"),
                        "poster_url": f"{IMAGE_BASE_URL}{best_match['poster_path']}" if best_match.get("poster_path") else None,
                        "backdrop_url": f"{BACKDROP_BASE_URL}{best_match['backdrop_path']}" if best_match.get("backdrop_path") else None
                    }
    except Exception as e:
        print(f"TMDB Search Error for '{query}': {e}")

    return None

async def search_tmdb_cached(db, title: str):
    """TMDB lookup backed by the local tmdb_cache table. Negative results are
    cached too, so each unique title costs at most one API call per TTL window."""
    cached = await get_cached_tmdb(db, title)
    if cached is not None:
        if cached["tmdb_id"] is None:
            return None
        return cached
    meta = await search_tmdb(title)
    await set_cached_tmdb(db, title, meta)
    return meta
