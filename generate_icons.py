import os
import sys
try:
    from PIL import Image, ImageDraw
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "Pillow"])
    from PIL import Image, ImageDraw

def create_circle_mask(size):
    mask = Image.new('L', size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0) + size, fill=255)
    return mask

def generate_icons(source_image_path, res_dir):
    if not os.path.exists(source_image_path):
        print(f"Error: Source image {source_image_path} not found.")
        return False

    if not os.path.exists(res_dir):
        print(f"Error: Resource directory {res_dir} not found.")
        return False

    # Define mipmap sizes
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
        "play_store": 512
    }

    try:
        with Image.open(source_image_path) as img:
            # Ensure image is square
            width, height = img.size
            if width != height:
                min_dim = min(width, height)
                left = (width - min_dim) / 2
                top = (height - min_dim) / 2
                right = (width + min_dim) / 2
                bottom = (height + min_dim) / 2
                img = img.crop((left, top, right, bottom))
            
            # Ensure RGBA for transparency in round icons
            img = img.convert("RGBA")

            for folder, size in sizes.items():
                if folder == "play_store":
                    folder_path = os.path.dirname(res_dir) # Put play store icon in src/main
                    icon_name = "ic_launcher_play_store.png"
                else:
                    folder_path = os.path.join(res_dir, folder)
                    os.makedirs(folder_path, exist_ok=True)
                    icon_name = "ic_launcher.png"
                    round_icon_name = "ic_launcher_round.png"

                # Resize image (LANCZOS for high quality downsampling)
                resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
                
                if folder != "play_store":
                    # Save standard square/adaptive icon
                    resized_img.save(os.path.join(folder_path, icon_name), "PNG")
                    print(f"Saved {icon_name} to {folder_path} ({size}x{size})")

                    # Create and save round icon
                    round_img = resized_img.copy()
                    mask = create_circle_mask((size, size))
                    round_img.putalpha(mask)
                    round_img.save(os.path.join(folder_path, round_icon_name), "PNG")
                    print(f"Saved {round_icon_name} to {folder_path} ({size}x{size})")
                else:
                    # Save play store icon
                    resized_img.save(os.path.join(folder_path, icon_name), "PNG")
                    print(f"Saved {icon_name} to {folder_path} ({size}x{size})")

        print("Successfully generated all Android mipmap icons.")
        return True

    except Exception as e:
        print(f"Exception occurred while generating icons: {e}")
        return False

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description='Generate Android mipmap icons.')
    parser.add_argument('--source', required=True, help='Path to the source high-res image')
    parser.add_argument('--res', required=True, help='Path to the Android app res directory')
    args = parser.parse_args()
    
    generate_icons(args.source, args.res)
