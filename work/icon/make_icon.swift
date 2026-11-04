// Draws the ShelfLife icon: a vertical slice of film whose frames are shelves of DVDs.
// Run: swift work/icon/make_icon.swift work/icon/AppIcon-1024.png
import AppKit
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

// macOS icon grid: 824 pt rounded square centred in 1024.
let tile = NSBezierPath(roundedRect: NSRect(x: 100, y: 100, width: 824, height: 824), xRadius: 185, yRadius: 185)
// Dark, desaturated blue, a touch lighter at the centre: the projector's beam coming through the
// film toward the viewer, kept very subtle.
NSGradient(colors: [NSColor(calibratedRed: 0.20, green: 0.25, blue: 0.33, alpha: 1),
                    NSColor(calibratedRed: 0.11, green: 0.14, blue: 0.20, alpha: 1),
                    NSColor(calibratedRed: 0.06, green: 0.075, blue: 0.11, alpha: 1)],
           atLocations: [0, 0.55, 1], colorSpace: .deviceRGB)!.draw(in: tile, relativeCenterPosition: NSPoint(x: 0, y: 0.02))
NSGraphicsContext.saveGraphicsState()
tile.addClip()
// A faint warm halo right behind the strip, where the lamp shines through.
let lamp = NSColor(calibratedRed: 0.95, green: 0.90, blue: 0.78, alpha: 1)
NSGradient(colors: [lamp.withAlphaComponent(0.09), lamp.withAlphaComponent(0.035), lamp.withAlphaComponent(0)], atLocations: [0, 0.35, 0.75], colorSpace: .deviceRGB)!
    .draw(in: NSRect(x: 100, y: 100, width: 824, height: 824), relativeCenterPosition: .zero)

// A short slice of film sitting inside the tile.
let strip = NSRect(x: 302, y: 168, width: 420, height: 688)
NSColor(calibratedWhite: 0.11, alpha: 1).setFill()
NSBezierPath(roundedRect: strip, xRadius: 22, yRadius: 22).fill()

// Sprocket holes down both edges.
NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
let holes = Int((strip.height - 40 - 44) / 76) + 1
var y = strip.minY + (strip.height - CGFloat(holes - 1) * 76 - 44) / 2  // centred along the strip
for _ in 0..<holes {
    for x in [strip.minX + 18, strip.maxX - 18 - 34] {
        NSBezierPath(roundedRect: NSRect(x: x, y: y, width: 34, height: 44), xRadius: 8, yRadius: 8).fill()
    }
    y += 76
}

// Three frames, each a shelf cubby holding a few DVD spines.
let palette: [NSColor] = [.systemRed, .systemGreen, .systemBlue, .systemYellow]
let frameX = strip.minX + 72, frameW = strip.width - 144
for (row, frameY) in [CGFloat(630), 422, 214].enumerated() {
    let frame = NSRect(x: frameX, y: frameY, width: frameW, height: 180)
    NSColor(calibratedWhite: 0.2, alpha: 1).setFill()
    NSBezierPath(roundedRect: frame, xRadius: 14, yRadius: 14).fill()
    // Shelf board along the bottom of the frame.
    NSColor(calibratedWhite: 0.92, alpha: 1).setFill()
    NSBezierPath(roundedRect: NSRect(x: frame.minX + 14, y: frame.minY + 16, width: frame.width - 28, height: 16), xRadius: 8, yRadius: 8).fill()
    // Spines standing on the board, slightly varied heights; the last one leans.
    var x = frame.minX + 30
    for i in 0..<5 {
        let height: CGFloat = [124, 110, 132, 116, 104][(i + row) % 5]
        let spine = NSBezierPath(roundedRect: NSRect(x: x, y: frame.minY + 32, width: 30, height: height), xRadius: 6, yRadius: 6)
        palette[(i + row) % palette.count].blended(withFraction: 0.2, of: .black)!.setFill()
        if i == 4 {
            // Pivot on its bottom-left corner (resting on the board) so the top rests against the previous spine.
            let pivot = NSPoint(x: x + 22, y: frame.minY + 32)
            spine.transform(using: AffineTransform(translationByX: 22, byY: 0))
            let lean = NSAffineTransform()
            lean.translateX(by: pivot.x, yBy: pivot.y)
            lean.rotate(byDegrees: 13)
            lean.translateX(by: -pivot.x, yBy: -pivot.y)
            spine.transform(using: lean as AffineTransform)
        }
        spine.fill()
        x += 40
    }
}
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "work/icon/AppIcon-1024.png"))
