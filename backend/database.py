import aiosqlite
import os
import time
from config import DATA_DIR

DB_PATH = os.path.join(DATA_DIR, "homelabtv.db")

# How long a TMDB lookup stays fresh before we re-query (7 days)
TMDB_CACHE_TTL_SECONDS = 7 * 24 * 60 * 60

async def get_db_connection():
    db = await aiosqlite.connect(DB_PATH)
    return db

async def init_db():
    db = await get_db_connection()
    await db.execute('''
        CREATE TABLE IF NOT EXISTS channel_mappings (
            physical_channel TEXT PRIMARY KEY,
            xmltv_id TEXT NOT NULL,
            custom_name TEXT,
            custom_image TEXT,
            xml_source TEXT
        )
    ''')
    await db.execute('''
        CREATE TABLE IF NOT EXISTS tmdb_cache (
            title TEXT PRIMARY KEY,
            tmdb_id INTEGER,
            poster_url TEXT,
            backdrop_url TEXT,
            overview TEXT,
            fetched_at INTEGER NOT NULL
        )
    ''')
    # Migration: uploaded channel logos live in their own column so they don't
    # collide with custom_image (which overrides program posters)
    cursor = await db.execute("PRAGMA table_info(channel_mappings)")
    columns = [row[1] for row in await cursor.fetchall()]
    if "custom_logo" not in columns:
        await db.execute("ALTER TABLE channel_mappings ADD COLUMN custom_logo TEXT")
    await db.commit()
    await db.close()

async def get_cached_tmdb(db, title: str):
    """Returns cached TMDB metadata for a title, or None on miss/expiry.
    A cached row with tmdb_id NULL means 'we searched and found nothing' —
    that negative result is cached too, so we don't hammer TMDB for titles
    like 'Local News at 6' on every guide request."""
    cursor = await db.execute(
        "SELECT tmdb_id, poster_url, backdrop_url, overview, fetched_at FROM tmdb_cache WHERE title = ?",
        (title,)
    )
    row = await cursor.fetchone()
    if row is None:
        return None
    tmdb_id, poster_url, backdrop_url, overview, fetched_at = row
    if time.time() - fetched_at > TMDB_CACHE_TTL_SECONDS:
        return None
    return {
        "hit": True,
        "tmdb_id": tmdb_id,
        "poster_url": poster_url,
        "backdrop_url": backdrop_url,
        "overview": overview,
    }

async def set_cached_tmdb(db, title: str, meta):
    """Stores a TMDB result (or a negative result when meta is None)."""
    await db.execute(
        "INSERT OR REPLACE INTO tmdb_cache (title, tmdb_id, poster_url, backdrop_url, overview, fetched_at) VALUES (?, ?, ?, ?, ?, ?)",
        (
            title,
            meta.get("tmdb_id") if meta else None,
            meta.get("poster_url") if meta else None,
            meta.get("backdrop_url") if meta else None,
            meta.get("overview") if meta else None,
            int(time.time()),
        )
    )
