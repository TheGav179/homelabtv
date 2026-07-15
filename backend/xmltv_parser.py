import xml.etree.ElementTree as ET
import os

def parse_xmltv(file_path):
    """Parses an XMLTV file and extracts the channel definitions."""
    if not os.path.exists(file_path):
        return []
    
    channels = []
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()
        
        for channel in root.findall('channel'):
            channel_id = channel.get('id')
            display_name = channel.find('display-name')
            name = display_name.text if display_name is not None else channel_id
            
            icon = channel.find('icon')
            icon_url = icon.get('src') if icon is not None else ""
            
            channels.append({
                "id": channel_id,
                "name": name,
                "icon": icon_url,
                "source_file": os.path.basename(file_path)
            })
    except Exception as e:
        print(f"Error parsing {file_path}: {e}")
        
    return channels

def parse_episode(prog):
    """Extracts a human-readable season/episode string ("S2 E6") from the
    programme's <episode-num> tags. Prefers the machine-readable xmltv_ns
    system (zero-based "season.episode.part"), falling back to whatever the
    onscreen system provides (usually already "S02E06" style)."""
    onscreen = None
    for ep in prog.findall('episode-num'):
        system = ep.get('system', '')
        text = (ep.text or "").strip()
        if not text:
            continue
        if system == 'xmltv_ns':
            parts = text.split('.')
            def _num(idx):
                if idx >= len(parts):
                    return None
                token = parts[idx].split('/')[0].strip()
                try:
                    return int(token) + 1  # xmltv_ns is zero-based
                except ValueError:
                    return None
            season, episode = _num(0), _num(1)
            if season and episode:
                return f"S{season} E{episode}"
            if episode:
                return f"E{episode}"
        elif system == 'onscreen':
            onscreen = text
    return onscreen

def parse_xmltv_programs(file_path):
    """Parses an XMLTV file and extracts the programme schedules."""
    if not os.path.exists(file_path):
        return []

    programs = []
    try:
        tree = ET.parse(file_path)
        root = tree.getroot()

        for prog in root.findall('programme'):
            channel_id = prog.get('channel')
            start = prog.get('start')
            stop = prog.get('stop')

            title_node = prog.find('title')
            title = title_node.text if title_node is not None else "Unknown Program"

            desc_node = prog.find('desc')
            desc = desc_node.text if desc_node is not None else ""

            # Episode name, e.g. <sub-title>The One With the Embassy Club</sub-title>
            sub_node = prog.find('sub-title')
            episode_title = sub_node.text.strip() if sub_node is not None and sub_node.text else None

            programs.append({
                "channel_id": channel_id,
                "start": start,
                "stop": stop,
                "title": title,
                "description": desc,
                "episode": parse_episode(prog),
                "episode_title": episode_title
            })
    except Exception as e:
        print(f"Error parsing programmes from {file_path}: {e}")

    return programs

