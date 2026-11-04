// Android TV home-screen banner (16:9): the icon artwork plus the name. Run after make_icon.swift.
import AppKit
let args = CommandLine.arguments
let icon = NSImage(contentsOfFile: args[1])!
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 640, pixelsHigh: 360, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
// Same dark projector-blue as the icon, brightest behind the film strip.
NSGradient(colors: [NSColor(calibratedRed: 0.20, green: 0.25, blue: 0.33, alpha: 1), NSColor(calibratedRed: 0.11, green: 0.14, blue: 0.20, alpha: 1), NSColor(calibratedRed: 0.06, green: 0.075, blue: 0.11, alpha: 1)],
           atLocations: [0, 0.5, 1], colorSpace: .deviceRGB)!.draw(in: NSRect(x: 0, y: 0, width: 640, height: 360), relativeCenterPosition: NSPoint(x: -0.55, y: 0))
// Crop the film strip out of the icon (it sits at x 302-722, y 168-856 of 1024).
icon.draw(in: NSRect(x: 48, y: 30, width: 183, height: 300), from: NSRect(x: 302, y: 168, width: 420, height: 688), operation: .sourceOver, fraction: 1)
("ShelfLife" as NSString).draw(at: NSPoint(x: 262, y: 148), withAttributes: [.font: NSFont.systemFont(ofSize: 66, weight: .bold), .foregroundColor: NSColor(calibratedWhite: 0.94, alpha: 1)])
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
