#!/usr/bin/env python3
"""
Generate deterministic T10-rewrite E2E fixtures without downloading external assets.

The no-text image is generated with the Python standard library only. If Pillow is
available, the text-bearing images include visible labels used by OCR assertions.
"""

from __future__ import annotations

import os
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "assets"
ROOT.mkdir(parents=True, exist_ok=True)


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    )


def write_png(path: Path, width: int, height: int, pixels: list[tuple[int, int, int]], compress_level: int = 9) -> None:
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixels[y * width + x])
    data = b"\x89PNG\r\n\x1a\n"
    data += _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    data += _png_chunk(b"IDAT", zlib.compress(bytes(raw), compress_level))
    data += _png_chunk(b"IEND", b"")
    path.write_bytes(data)


def geometric_png(
    path: Path,
    width: int = 640,
    height: int = 420,
    variant: int = 0,
    compress_level: int = 9,
    noisy: bool = False,
) -> None:
    pixels: list[tuple[int, int, int]] = []
    for y in range(height):
        for x in range(width):
            bg = 246 - (y * 10 // max(1, height))
            r, g, b = bg, bg + 2, 250
            left_a = int(width * 0.11) + variant * int(width * 0.02)
            right_a = int(width * 0.39) + variant * int(width * 0.02)
            left_b = int(width * 0.53) - variant * int(width * 0.015)
            right_b = int(width * 0.88) - variant * int(width * 0.015)
            top_a = int(height * 0.19)
            bottom_a = int(height * 0.40)
            mid_left = int(width * 0.30)
            mid_right = int(width * 0.71)
            mid_top = int(height * 0.60) + variant * int(height * 0.012)
            mid_bottom = int(height * 0.83) + variant * int(height * 0.012)
            if left_a < x < right_a and top_a < y < bottom_a:
                r, g, b = 55, 126, 184
            if left_b < x < right_b and top_a < y < bottom_a + int(height * 0.03):
                r, g, b = 77, 175, 74
            if mid_left < x < mid_right and mid_top < y < mid_bottom:
                r, g, b = 152, 78, 163
            if abs((y - int(height * 0.52)) - (x - int(width * 0.12)) * 0.22) < 3 and int(width * 0.12) < x < int(width * 0.88):
                r, g, b = 70, 70, 70
            if abs((y - int(height * 0.52)) + (x - int(width * 0.88)) * 0.25) < 3 and int(width * 0.14) < x < int(width * 0.88):
                r, g, b = 70, 70, 70
            if noisy and (x + y + variant) % 7 == 0:
                r = max(0, min(255, r + ((x * 17 + y * 13 + variant) % 9) - 4))
                g = max(0, min(255, g + ((x * 11 + y * 19 + variant) % 9) - 4))
                b = max(0, min(255, b + ((x * 23 + y * 5 + variant) % 9) - 4))
            pixels.append((r, g, b))
    write_png(path, width, height, pixels, compress_level=compress_level)


def tiny_icon(path: Path) -> None:
    pixels = []
    for y in range(32):
        for x in range(32):
            pixels.append((30, 144, 255) if (x - 16) ** 2 + (y - 16) ** 2 < 120 else (255, 255, 255))
    write_png(path, 32, 32, pixels)


def pillow_image(path: Path, text: str, variant: int = 0) -> bool:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except Exception:
        return False

    img = Image.new("RGB", (760, 480), (248, 250, 252))
    draw = ImageDraw.Draw(img)
    colors = [(52, 109, 219), (16, 163, 127), (217, 119, 6)]
    boxes = [(60 + variant * 10, 90, 250 + variant * 10, 175), (485 - variant * 8, 90, 690 - variant * 8, 175), (250, 295, 515, 385)]
    for i, box in enumerate(boxes):
        draw.rounded_rectangle(box, radius=12, fill=colors[i], outline=(20, 20, 20), width=2)
    draw.line((250 + variant * 10, 132, 485 - variant * 8, 132), fill=(35, 35, 35), width=5)
    draw.line((380, 175, 380, 295), fill=(35, 35, 35), width=5)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 38)
        small = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 26)
    except Exception:
        font = ImageFont.load_default()
        small = ImageFont.load_default()
    draw.text((65, 28), text, fill=(15, 23, 42), font=font)
    draw.text((95 + variant * 10, 118), "Spring Boot", fill=(255, 255, 255), font=small)
    draw.text((515 - variant * 8, 118), "PostgreSQL", fill=(255, 255, 255), font=small)
    draw.text((300, 323), "RAGForge", fill=(255, 255, 255), font=small)
    img.save(path)
    return True


def minimal_pdf(path: Path, title: str, lines: list[str], image_names: list[str] | None = None) -> None:
    text = "\\n".join(lines)
    image_note = ""
    if image_names:
        image_note = "\\nEmbedded figures: " + ", ".join(image_names)
    content = f"BT /F1 16 Tf 72 760 Td ({title}) Tj /F1 11 Tf 0 -32 Td ({text}{image_note}) Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        f"<< /Length {len(content.encode('latin-1', 'ignore'))} >>\nstream\n{content}\nendstream".encode("latin-1", "ignore"),
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode())
        out.extend(obj)
        out.extend(b"\nendobj\n")
    xref = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode())
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode())
    out.extend(f"trailer << /Root 1 0 R /Size {len(objects)+1} >>\nstartxref\n{xref}\n%%EOF\n".encode())
    path.write_bytes(bytes(out))


def _load_rgb(path: Path) -> tuple[int, int, bytes]:
    try:
        from PIL import Image

        img = Image.open(path).convert("RGB")
        return img.width, img.height, img.tobytes()
    except Exception:
        data = path.read_bytes()
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError(f"unsupported image format: {path}")
        offset = 8
        width = height = None
        compressed = bytearray()
        while offset < len(data):
            length = struct.unpack(">I", data[offset : offset + 4])[0]
            kind = data[offset + 4 : offset + 8]
            chunk = data[offset + 8 : offset + 8 + length]
            offset += 12 + length
            if kind == b"IHDR":
                width, height, bit_depth, color_type, *_ = struct.unpack(">IIBBBBB", chunk)
                if bit_depth != 8 or color_type != 2:
                    raise ValueError(f"unsupported PNG color type: {path}")
            elif kind == b"IDAT":
                compressed.extend(chunk)
            elif kind == b"IEND":
                break
        if width is None or height is None:
            raise ValueError(f"invalid PNG: {path}")
        raw = zlib.decompress(bytes(compressed))
        stride = width * 3
        rgb = bytearray()
        for y in range(height):
            filter_type = raw[y * (stride + 1)]
            if filter_type != 0:
                raise ValueError(f"unsupported PNG filter {filter_type}: {path}")
            rgb.extend(raw[y * (stride + 1) + 1 : y * (stride + 1) + 1 + stride])
        return width, height, bytes(rgb)


def mixed_pdf_with_images(path: Path, title: str, lines: list[str], image_paths: list[Path]) -> None:
    images = []
    for image_path in image_paths:
        width, height, rgb = _load_rgb(image_path)
        compressed = zlib.compress(rgb, 9)
        images.append((width, height, compressed))

    image_obj_start = 5
    image_names = [f"Im{i + 1}" for i in range(len(images))]
    xobjects = " ".join(f"/{name} {image_obj_start + i} 0 R" for i, name in enumerate(image_names))
    page = (
        f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        f"/Resources << /Font << /F1 4 0 R >> /XObject << {xobjects} >> >> "
        f"/Contents {image_obj_start + len(images)} 0 R >>"
    ).encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        page,
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    for width, height, compressed in images:
        objects.append(
            (
                f"<< /Type /XObject /Subtype /Image /Width {width} /Height {height} "
                f"/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length {len(compressed)} >>\n"
            ).encode()
            + b"stream\n"
            + compressed
            + b"\nendstream"
        )

    text_ops = f"BT /F1 16 Tf 72 750 Td ({title}) Tj /F1 11 Tf 0 -26 Td ({' '.join(lines)}) Tj ET\n"
    draw_ops = [
        "q 150 0 0 95 55 500 cm /Im1 Do Q",
        "q 150 0 0 95 230 500 cm /Im2 Do Q",
        "q 150 0 0 95 405 500 cm /Im3 Do Q",
    ]
    content = (text_ops + "\n".join(draw_ops)).encode("latin-1", "ignore")
    objects.append(
        f"<< /Length {len(content)} >>\nstream\n".encode()
        + content
        + b"\nendstream"
    )

    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode())
        out.extend(obj)
        out.extend(b"\nendobj\n")
    xref = len(out)
    out.extend(f"xref\n0 {len(objects)+1}\n0000000000 65535 f \n".encode())
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode())
    out.extend(f"trailer << /Root 1 0 R /Size {len(objects)+1} >>\nstartxref\n{xref}\n%%EOF\n".encode())
    path.write_bytes(bytes(out))


def main() -> None:
    geometric_png(ROOT / "image_no_text.png", variant=0)
    geometric_png(ROOT / "architecture_v1.png", width=420, height=280, variant=0, noisy=True)
    geometric_png(ROOT / "architecture_v2.png", width=420, height=280, variant=1, noisy=True)
    tiny_icon(ROOT / "small_icon.png")

    if not pillow_image(ROOT / "image_with_text.png", "RAGForge 架构图", variant=0):
        geometric_png(ROOT / "image_with_text.png", variant=2)

    minimal_pdf(
        ROOT / "text_only.pdf",
        "RAGForge text-only fixture",
        [
            "RAGForge unified vector search validates Spring Boot and PostgreSQL retrieval. "
            "The first paragraph explains the upload controller, object storage handoff, "
            "document registration, worker state machine, and vector indexing path in a "
            "single end to end flow that should be searchable after processing completes.",
            "The second paragraph is intentionally longer so the recursive chunker emits "
            "multiple text chunks under the default profile. It mentions parser, cleaner, "
            "chunker, embedder, indexer, Elasticsearch, PostgreSQL, and pgvector so E2E "
            "search can use stable keywords without relying on generated wording.",
            "The third paragraph describes system architecture containing Spring Boot, "
            "PostgreSQL, Redis, RocketMQ, DashScope embeddings, qwen3 vl embedding, and "
            "the unified vl vector column. This paragraph verifies that text-only files "
            "still use TEXT modality after the multimodal rewrite.",
            "The fourth paragraph adds enough deterministic plain English and Chinese "
            "tokens for chunk boundaries: ingestion pipeline, retrieval service, hybrid "
            "search, vector search, reranking, knowledge base permission checks, and "
            "document detail status badges should all remain compatible.",
            "The fifth paragraph closes the fixture with repeated architecture context "
            "for recall validation. Spring Boot services read from PostgreSQL, store "
            "chunks with vl_vector dimension 2560, and return ranked chunks through the "
            "DebugConsole search API.",
        ],
    )
    mixed_pdf_with_images(
        ROOT / "mixed_3figures.pdf",
        "RAGForge mixed fixture",
        [
            "This PDF describes a system architecture with three embedded figures.",
            "Figure one is ingestion, figure two is retrieval, figure three is monitoring.",
            "Text chunks and image chunks must coexist when image processing mode is ON.",
        ],
        [ROOT / "architecture_v1.png", ROOT / "architecture_v2.png", ROOT / "image_with_text.png"],
    )
    print(f"fixtures generated under {ROOT}")


if __name__ == "__main__":
    main()
