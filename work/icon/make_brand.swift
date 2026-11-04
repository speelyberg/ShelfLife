// ShelfLife brand art, all from one drawing of the logo (the film strip whose frames are shelves of DVDs).
// Run: swift work/icon/make_brand.swift <android res dir> <work/icon dir>
// Writes:
//   drawable-nodpi/logo.png                   the logo alone, transparent (loading screen, banner)
//   drawable-nodpi/ic_launcher_background.png full-bleed square radial gradient (adaptive icon back layer)
//   drawable-nodpi/ic_launcher_foreground.png logo centred inside the adaptive-icon safe zone, transparent
//   drawable-nodpi/banner.png                 Android TV home-screen banner
//   <work/icon>/AppIcon-square.png            flattened full-bleed square (OSes crop to their own shape)
//   <work/icon>/banner@2x.png                 the banner at 1280 x 720, for the web page (hosting/worker.js)
import AppKit

let args = CommandLine.arguments
let res = args.count > 1 ? args[1] : "android/app/src/main/res"
let work = args.count > 2 ? args[2] : "work/icon"

/// A w x h point canvas drawn at `scale` pixels per point.
func bitmap(_ w: Int, _ h: Int, scale: Int = 1, _ draw: () -> Void) -> NSBitmapImageRep {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w * scale, pixelsHigh: h * scale, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: w, height: h)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    draw()
    NSGraphicsContext.restoreGraphicsState()
    return rep
}
func save(_ rep: NSBitmapImageRep, _ path: String) {
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

/// The logo in a 420 x 688 box with its lower-left corner at `origin`, scaled by `s`.
func drawLogo(at origin: NSPoint, scale s: CGFloat) {
    let t = NSAffineTransform(); t.translateX(by: origin.x, yBy: origin.y); t.scale(by: s)
    NSGraphicsContext.saveGraphicsState(); t.concat()
    let strip = NSRect(x: 0, y: 0, width: 420, height: 688)
    NSColor(calibratedWhite: 0.11, alpha: 1).setFill()
    NSBezierPath(roundedRect: strip, xRadius: 22, yRadius: 22).fill()
    NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
    let holes = Int((strip.height - 40 - 44) / 76) + 1
    var y = (strip.height - CGFloat(holes - 1) * 76 - 44) / 2
    for _ in 0..<holes {
        for x in [18.0, strip.width - 18 - 34] { NSBezierPath(roundedRect: NSRect(x: x, y: y, width: 34, height: 44), xRadius: 8, yRadius: 8).fill() }
        y += 76
    }
    let palette: [NSColor] = [.systemRed, .systemGreen, .systemBlue, .systemYellow]
    for (row, frameY) in [CGFloat(462), 254, 46].enumerated() {
        let frame = NSRect(x: 72, y: frameY, width: strip.width - 144, height: 180)
        NSColor(calibratedWhite: 0.2, alpha: 1).setFill()
        NSBezierPath(roundedRect: frame, xRadius: 14, yRadius: 14).fill()
        NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: frame.minX + 14, y: frame.minY + 16, width: frame.width - 28, height: 16), xRadius: 8, yRadius: 8).fill()
        var x = frame.minX + 30
        for i in 0..<5 {
            let height: CGFloat = [124, 110, 132, 116, 104][(i + row) % 5]
            let spine = NSBezierPath(roundedRect: NSRect(x: x, y: frame.minY + 32, width: 30, height: height), xRadius: 6, yRadius: 6)
            palette[(i + row) % palette.count].blended(withFraction: 0.2, of: .black)!.setFill()
            if i == 4 {  // leans against the previous spine
                let pivot = NSPoint(x: x + 22, y: frame.minY + 32)  // bottom-left corner, so it rests on the board
                spine.transform(using: AffineTransform(translationByX: 22, byY: 0))
                let lean = NSAffineTransform(); lean.translateX(by: pivot.x, yBy: pivot.y); lean.rotate(byDegrees: 13); lean.translateX(by: -pivot.x, yBy: -pivot.y)
                spine.transform(using: lean as AffineTransform)
            }
            spine.fill()
            x += 40
        }
    }
    NSGraphicsContext.restoreGraphicsState()
}

/// Dark, desaturated projector-blue, a touch lighter in the middle, with a faint warm glow.
func drawBackdrop(_ rect: NSRect, center: NSPoint = .zero) {
    NSGradient(colors: [NSColor(calibratedRed: 0.20, green: 0.25, blue: 0.33, alpha: 1),
                        NSColor(calibratedRed: 0.11, green: 0.14, blue: 0.20, alpha: 1),
                        NSColor(calibratedRed: 0.06, green: 0.075, blue: 0.11, alpha: 1)],
               atLocations: [0, 0.55, 1], colorSpace: .deviceRGB)!.draw(in: rect, relativeCenterPosition: center)
    let lamp = NSColor(calibratedRed: 0.95, green: 0.90, blue: 0.78, alpha: 1)
    NSGradient(colors: [lamp.withAlphaComponent(0.09), lamp.withAlphaComponent(0.035), lamp.withAlphaComponent(0)], atLocations: [0, 0.35, 0.75], colorSpace: .deviceRGB)!
        .draw(in: rect, relativeCenterPosition: center)
}

let nodpi = res + "/drawable-nodpi"
// Logo alone: 420 x 688 art with a little breathing room.
save(bitmap(460, 728) { drawLogo(at: NSPoint(x: 20, y: 20), scale: 1) }, nodpi + "/logo.png")

// Adaptive icon (432 px = 108 dp at xxxhdpi). Launchers crop anywhere outside the central 66 dp circle,
// so the logo is sized so its corners stay inside that circle (diameter 264 px).
let size = 432
save(bitmap(size, size) { drawBackdrop(NSRect(x: 0, y: 0, width: size, height: size)) }, nodpi + "/ic_launcher_background.png")
let logoH: CGFloat = 212, logoS = logoH / 688, logoW = 420 * logoS
let logoOrigin = NSPoint(x: (CGFloat(size) - logoW) / 2, y: (CGFloat(size) - logoH) / 2)
save(bitmap(size, size) { drawLogo(at: logoOrigin, scale: logoS) }, nodpi + "/ic_launcher_foreground.png")

// Flattened full-bleed square (1024) for anything that wants a plain image.
save(bitmap(1024, 1024) {
    drawBackdrop(NSRect(x: 0, y: 0, width: 1024, height: 1024))
    let h: CGFloat = 1024 * 212 / 432
    drawLogo(at: NSPoint(x: (1024 - 420 * h / 688) / 2, y: (1024 - h) / 2), scale: h / 688)
}, work + "/AppIcon-square.png")

// TV banner (320 x 180 dp → 640 x 360): logo on the left, name on the right. Also drawn at 2x for the web.
func drawBanner() {
    drawBackdrop(NSRect(x: 0, y: 0, width: 640, height: 360), center: NSPoint(x: -0.55, y: 0))
    drawLogo(at: NSPoint(x: 52, y: 30), scale: 300 / 688)
    ("ShelfLife" as NSString).draw(at: NSPoint(x: 262, y: 148), withAttributes: [.font: NSFont.systemFont(ofSize: 66, weight: .bold), .foregroundColor: NSColor(calibratedWhite: 0.94, alpha: 1)])
}
save(bitmap(640, 360, drawBanner), nodpi + "/banner.png")
save(bitmap(640, 360, scale: 2, drawBanner), work + "/banner@2x.png")
print("brand art written")
