import os
from fastapi import FastAPI, File, HTTPException, Request, UploadFile
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
import uvicorn
import xml.etree.ElementTree as ET
from xmltv_parser import parse_xmltv, parse_xmltv_programs
from database import get_db_connection, init_db
from config import TMDB_API_KEY, DATA_DIR
import tmdb

app = FastAPI(title="HomelabTV Backend")

# We will serve the Web UI from a 'static' and 'templates' folder
if not os.path.exists("static"): os.makedirs("static")
if not os.path.exists("templates"): os.makedirs("templates")

app.mount("/static", StaticFiles(directory="static"), name="static")
templates = Jinja2Templates(directory="templates")

@app.on_event("startup")
async def startup():
    await init_db()

@app.get("/")
async def ui_home(request: Request):
    """Serve the Web UI for channel mapping."""
    return templates.TemplateResponse(request=request, name="index.html", context={"request": request})

def xmltv_files():
    """Every .xml file in the data directory counts as an XMLTV source.
    Files that aren't valid XMLTV simply contribute no channels/programs."""
    try:
        return sorted(
            os.path.join(DATA_DIR, name)
            for name in os.listdir(DATA_DIR)
            if name.lower().endswith(".xml")
        )
    except OSError:
        return []

@app.get("/api/xmltv/channels")
async def get_xmltv_channels():
    """Reads every XMLTV file in the data directory and returns the available channels."""
    channels = []
    for path in xmltv_files():
        channels.extend(parse_xmltv(path))

    # Deduplicate
    unique_channels = {c["id"]: c for c in channels}
    return list(unique_channels.values())

class MappingRequest(BaseModel):
    physical_channel: str
    xmltv_id: str
    custom_name: str | None = None
    custom_image: str | None = None
    xml_source: str | None = None

@app.post("/api/mappings")
async def save_mapping(mapping: MappingRequest):
    """Saves a mapping from physical ATSC channel to XMLTV ID with custom overrides."""
    db = await get_db_connection()
    await db.execute(
        "INSERT OR REPLACE INTO channel_mappings (physical_channel, xmltv_id, custom_name, custom_image, xml_source) VALUES (?, ?, ?, ?, ?)",
        (mapping.physical_channel, mapping.xmltv_id, mapping.custom_name, mapping.custom_image, mapping.xml_source)
    )
    await db.commit()
    await db.close()
    return {"status": "success"}

@app.get("/api/mappings")
async def get_mappings():
    """Gets all current channel mappings and custom overrides."""
    db = await get_db_connection()
    cursor = await db.execute("SELECT physical_channel, xmltv_id, custom_name, custom_image, xml_source, custom_logo FROM channel_mappings")
    rows = await cursor.fetchall()
    await db.close()
    return [{
        "physical_channel": row[0],
        "xmltv_id": row[1],
        "custom_name": row[2],
        "custom_image": row[3],
        "xml_source": row[4],
        "custom_logo": row[5]
    } for row in rows]

@app.delete("/api/mappings/{physical_channel}")
async def delete_mapping(physical_channel: str):
    """Removes a physical-to-XMLTV channel mapping."""
    db = await get_db_connection()
    cursor = await db.execute("DELETE FROM channel_mappings WHERE physical_channel = ?", (physical_channel,))
    await db.commit()
    deleted = cursor.rowcount
    await db.close()
    if deleted == 0:
        raise HTTPException(status_code=404, detail=f"No mapping exists for physical channel '{physical_channel}'")
    return {"status": "success"}

ALLOWED_LOGO_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}

@app.post("/api/logo/{physical_channel}")
async def upload_logo(physical_channel: str, file: UploadFile = File(...)):
    """Uploads a custom channel logo image and attaches it to an existing mapping."""
    ext = os.path.splitext(file.filename or "")[1].lower()
    if ext not in ALLOWED_LOGO_EXTENSIONS:
        raise HTTPException(status_code=400, detail=f"Unsupported image type '{ext}'. Use one of: {sorted(ALLOWED_LOGO_EXTENSIONS)}")

    db = await get_db_connection()
    cursor = await db.execute("SELECT 1 FROM channel_mappings WHERE physical_channel = ?", (physical_channel,))
    exists = await cursor.fetchone()
    if not exists:
        await db.close()
        raise HTTPException(status_code=404, detail=f"No mapping exists for physical channel '{physical_channel}'")

    os.makedirs(os.path.join("static", "logos"), exist_ok=True)
    safe_name = "".join(c if c.isalnum() else "_" for c in physical_channel)
    logo_path = f"/static/logos/{safe_name}{ext}"
    with open(os.path.join("static", "logos", f"{safe_name}{ext}"), "wb") as f:
        f.write(await file.read())

    await db.execute("UPDATE channel_mappings SET custom_logo = ? WHERE physical_channel = ?", (logo_path, physical_channel))
    await db.commit()
    await db.close()
    return {"status": "success", "logo_url": logo_path}

@app.get("/api/guide")
async def get_enriched_guide(request: Request):
    """The master endpoint for the Android TV app. Returns physical channels merged
    with XMLTV schedule data, channel logos, and TMDB artwork (served from the
    local tmdb_cache so repeat requests are fast and cheap)."""
    # 1. Get mappings with overrides
    db = await get_db_connection()
    cursor = await db.execute("SELECT physical_channel, xmltv_id, custom_name, custom_image, xml_source, custom_logo FROM channel_mappings")
    mappings = {row[0]: {"xmltv_id": row[1], "custom_name": row[2], "custom_image": row[3], "xml_source": row[4], "custom_logo": row[5]} for row in await cursor.fetchall()}

    # 2. Get all XMLTV channels (for names/logos) and programs from every XML source
    xmltv_channels = {}
    all_programs = []
    for path in xmltv_files():
        for ch in parse_xmltv(path):
            xmltv_channels[ch["id"]] = ch
        all_programs.extend(parse_xmltv_programs(path))

    # Group programs by channel once instead of scanning the full list per channel
    programs_by_channel = {}
    for p in all_programs:
        programs_by_channel.setdefault(p["channel_id"], []).append(p)

    enriched_guide = []

    # 3. For each physical channel, attach its full schedule and TMDB artwork
    for physical_ch, override_data in mappings.items():
        xml_id = override_data["xmltv_id"]
        ch_programs = sorted(programs_by_channel.get(xml_id, []), key=lambda p: p["start"] or "")
        xmltv_ch = xmltv_channels.get(xml_id, {})

        # Enrich once per unique title — the cache makes this cheap after the first run
        title_meta = {}
        for prog in ch_programs:
            title = prog["title"]
            if title not in title_meta:
                title_meta[title] = await tmdb.search_tmdb_cached(db, title)

        enriched_programs = []
        for prog in ch_programs:
            tmdb_meta = title_meta.get(prog["title"])
            prog_data = {
                "title": prog["title"],
                "start": prog["start"],
                "stop": prog["stop"],
                "description": prog["description"],
                "episode": prog.get("episode"),
                "poster_url": None,
                "backdrop_url": None,
            }
            if tmdb_meta:
                prog_data["poster_url"] = override_data["custom_image"] if override_data["custom_image"] else tmdb_meta["poster_url"]
                prog_data["backdrop_url"] = tmdb_meta["backdrop_url"]
            enriched_programs.append(prog_data)

        # Uploaded logo wins, then poster override, then the XMLTV icon.
        # Local /static paths are absolutized so the TV app can load them.
        logo = override_data["custom_logo"] or override_data["custom_image"] or xmltv_ch.get("icon") or None
        if logo and logo.startswith("/"):
            logo = str(request.base_url).rstrip("/") + logo

        enriched_guide.append({
            "physical_channel": physical_ch,
            "xmltv_id": xml_id,
            "name": override_data["custom_name"] or xmltv_ch.get("name") or physical_ch,
            "logo_url": logo,
            "xml_source": override_data["xml_source"],
            "programs": enriched_programs
        })

    await db.commit()  # persist any new tmdb_cache rows written during enrichment
    await db.close()
    return enriched_guide

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=30060, reload=True)
