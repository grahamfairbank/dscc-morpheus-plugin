#!/usr/bin/env python3
"""Screenshots of the PREVIEW_OUT renders for docs/images (run inside a Playwright image).

  PREVIEW_OUT=build/preview: render with PreviewSpec (synthetic test data only, never real data)
  docker run --rm -v $PWD/build/preview:/preview:ro -v $PWD/docs/images:/out -v $PWD/tools:/tools:ro \
      --entrypoint python3 <playwright-image> /tools/screenshots.py
"""
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": 1280, "height": 400}, device_scale_factor=1)
    for name in ("dscc-cluster", "dscc-host", "dscc-guest"):
        pg.goto(f"file:///preview/{name}.html")
        pg.evaluate("document.querySelectorAll('details').forEach(d => d.open = true)")
        pg.locator(".dscc-morpheus").first.screenshot(path=f"/out/{name.replace('dscc-', 'tab-')}.png")
    b.close()
