"""Generate the mod's original 64x64 entity textures with only the Python stdlib.

The rectangles follow the UV layout in GoblinModel.createLayer(). Run this
script after changing that layout so the checked-in PNGs stay reproducible.
"""

from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/goblin_settlement/textures/entity"
SIZE = 64


class Canvas:
    def __init__(self):
        self.pixels = [bytearray(SIZE * 4) for _ in range(SIZE)]

    def rect(self, x, y, width, height, color):
        for row in range(y, y + height):
            for column in range(x, x + width):
                self.pixels[row][column * 4:column * 4 + 4] = bytes((*color, 255))

    def box(self, u, v, width, height, depth, top, front, side, back=None):
        # Minecraft cube UV: top/bottom, then left/front/right/back.
        self.rect(u + depth, v, width, depth, top)
        self.rect(u + depth + width, v, width, depth, side)
        self.rect(u, v + depth, depth, height, side)
        self.rect(u + depth, v + depth, width, height, front)
        self.rect(u + depth + width, v + depth, depth, height, side)
        self.rect(u + depth + width + depth, v + depth, width, height, back or front)

    def save(self, path):
        def chunk(kind, data):
            return (struct.pack(">I", len(data)) + kind + data
                    + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff))

        raw = b"".join(b"\0" + row for row in self.pixels)
        png = (b"\x89PNG\r\n\x1a\n"
               + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
               + chunk(b"IDAT", zlib.compress(raw, 9))
               + chunk(b"IEND", b""))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(png)


def goblin(accents=()):
    image = Canvas()
    skin, light, shade = (86, 145, 62), (119, 175, 78), (59, 105, 48)
    image.box(0, 0, 8, 8, 8, light, skin, shade)
    image.rect(10, 11, 2, 2, (23, 28, 18))
    image.rect(14, 11, 2, 2, (23, 28, 18))
    image.rect(10, 11, 1, 1, (245, 218, 91))
    image.rect(15, 11, 1, 1, (245, 218, 91))
    image.rect(11, 15, 4, 1, (41, 74, 36))
    image.rect(8, 8, 8, 2, (74, 59, 39))
    image.box(16, 16, 6, 8, 4, (131, 91, 49), (108, 68, 39), (80, 51, 35))
    image.rect(22, 25, 2, 5, (170, 121, 58))
    image.rect(20, 29, 6, 1, (59, 44, 30))
    image.box(40, 16, 3, 8, 3, (124, 84, 47), (109, 72, 40), (77, 51, 35))
    image.rect(43, 26, 3, 1, (54, 96, 43))
    image.rect(43, 27, 3, 3, skin)
    image.box(0, 16, 3, 8, 3, (75, 60, 47), (69, 57, 51), (48, 43, 40))
    image.rect(3, 27, 3, 3, (52, 39, 32))
    for accent in accents:
        accent(image)
    return image


def golem():
    image = Canvas()
    stone, light, shadow = (107, 112, 104), (145, 151, 137), (73, 79, 75)
    image.box(0, 0, 8, 8, 8, light, stone, shadow)
    image.rect(9, 10, 3, 2, (37, 46, 42))
    image.rect(13, 10, 3, 2, (37, 46, 42))
    image.rect(10, 10, 1, 1, (226, 157, 69))
    image.rect(14, 10, 1, 1, (226, 157, 69))
    image.rect(10, 15, 6, 1, (55, 60, 57))
    image.box(16, 16, 6, 8, 4, light, stone, shadow)
    image.rect(22, 24, 2, 5, (144, 92, 52))
    image.rect(20, 28, 6, 1, (54, 63, 61))
    image.box(40, 16, 3, 8, 3, light, stone, shadow)
    image.rect(43, 27, 3, 2, (71, 79, 76))
    image.box(0, 16, 3, 8, 3, light, stone, shadow)
    image.rect(3, 27, 3, 3, (69, 73, 68))
    return image


def straw_hat(image):
    brim, crown = (214, 187, 108), (190, 158, 82)
    image.rect(8, 0, 8, 8, crown)
    image.rect(8, 8, 8, 2, brim)


def shoulder_strap(image):
    strap = (92, 63, 38)
    image.rect(20, 20, 6, 2, strap)
    image.rect(24, 22, 2, 6, strap)


def headlamp(image):
    band, lamp, glow = (58, 55, 52), (245, 218, 91), (255, 255, 210)
    image.rect(8, 8, 8, 1, band)
    image.rect(11, 9, 2, 2, lamp)
    image.rect(11, 9, 1, 1, glow)


def tool_belt(image):
    belt, buckle, head = (74, 48, 28), (198, 166, 74), (150, 152, 156)
    image.rect(20, 24, 6, 2, belt)
    image.rect(22, 24, 2, 2, buckle)
    image.rect(24, 26, 2, 2, head)


def backpack(image):
    pack, strap = (110, 82, 48), (74, 55, 32)
    image.rect(30, 20, 6, 8, pack)
    image.rect(30, 20, 6, 1, strap)
    image.rect(31, 24, 4, 3, strap)


def apron_and_goggles(image):
    apron, glass, frame = (58, 62, 70), (137, 199, 208), (54, 50, 46)
    image.rect(21, 22, 4, 6, apron)
    image.rect(20, 27, 6, 1, apron)
    image.rect(9, 10, 3, 2, frame)
    image.rect(13, 10, 3, 2, frame)
    image.rect(10, 10, 1, 1, glass)
    image.rect(14, 10, 1, 1, glass)


def helmet(image):
    metal, rim, crest = (146, 151, 158), (96, 100, 108), (156, 66, 60)
    image.rect(8, 0, 8, 8, metal)
    image.rect(8, 8, 8, 2, rim)
    image.rect(10, 0, 2, 2, crest)


if __name__ == "__main__":
    goblin().save(ROOT / "goblin.png")
    goblin((straw_hat,)).save(ROOT / "goblin_farmer.png")
    goblin((shoulder_strap,)).save(ROOT / "goblin_forester.png")
    goblin((headlamp,)).save(ROOT / "goblin_miner.png")
    goblin((tool_belt,)).save(ROOT / "goblin_builder.png")
    goblin((backpack,)).save(ROOT / "goblin_hauler.png")
    goblin((apron_and_goggles,)).save(ROOT / "goblin_artisan.png")
    goblin((helmet,)).save(ROOT / "goblin_sentry.png")
    golem().save(ROOT / "goblin_golem.png")
