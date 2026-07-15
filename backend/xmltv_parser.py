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
            
            programs.append({
                "channel_id": channel_id,
                "start": start,
                "stop": stop,
                "title": title,
                "description": desc
            })
    except Exception as e:
        print(f"Error parsing programmes from {file_path}: {e}")
        
    return programs

