import bpy, math, random, os, sys, json
import numpy as np
from mathutils import Vector
from xml.etree.ElementTree import Element, SubElement, ElementTree

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'Sources/ShelfLife/Resources/LivingRoom')
OUTPUT = os.path.join(ROOT, 'outputs')
os.makedirs(ASSETS, exist_ok=True)
os.makedirs(OUTPUT, exist_ok=True)
random.seed(42)
bpy.ops.object.select_all(action='SELECT')
bpy.ops.object.delete(use_global=False)
for mat in list(bpy.data.materials): bpy.data.materials.remove(mat)

# This model uses ordinary Blender coordinates; the DAE exporter converts
# Blender (X, Y, Z) to app (X, Z, -Y). No room photographs are used as surfaces.
def texture(name, kind, color, size=512):
    rng = np.random.default_rng(218)
    xx, yy = np.meshgrid(np.arange(size)/size, np.arange(size)/size)
    fine = rng.random((size,size))-.5
    if kind == 'wood':
        grain = np.sin(yy*310 + np.sin(xx*12)*2 + np.sin(xx*40)*.6)
        noise = grain*.045 + fine*.035 + np.sin(yy*44+xx*3)*.025
    elif kind == 'granite':
        # Tan stone with dense brown speckle and a few darker flecks.
        blobs = rng.random((size//8,size//8)).repeat(8,0).repeat(8,1)-.5
        flecks = (rng.random((size,size)) > .965) * -.16
        noise = fine*.16 + blobs*.06 + flecks
    elif kind == 'carpet':
        noise = fine*.19 + np.sin(xx*1400+yy*390)*.022 + np.sin(yy*600)*.015
    else:
        noise = fine*.035 + np.sin(xx*100+yy*140)*.003
    rgba = np.ones((size,size,4), dtype=np.float32)
    for i,c in enumerate(color): rgba[:,:,i] = np.clip(c+noise,0,1)
    img = bpy.data.images.new(name, width=size, height=size, alpha=False)
    img.pixels.foreach_set(rgba.ravel())
    img.filepath_raw = os.path.join(ASSETS, name+'.png')
    img.file_format = 'PNG'; img.save()
    return img

def mat(name, color, rough=.65, metal=0, tex=None, bump=0):
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1)
    m.use_nodes=True; p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Roughness'].default_value=rough
    p.inputs['Metallic'].default_value=metal
    if tex:
        n=m.node_tree.nodes.new('ShaderNodeTexImage'); n.image=tex
        m.node_tree.links.new(n.outputs['Color'],p.inputs['Base Color'])
        if bump:
            b=m.node_tree.nodes.new('ShaderNodeBump')
            b.inputs['Strength'].default_value=bump; b.inputs['Distance'].default_value=.006
            m.node_tree.links.new(n.outputs['Color'],b.inputs['Height'])
            m.node_tree.links.new(b.outputs['Normal'],p.inputs['Normal'])
    return m

plaster=mat('Soft_white_plaster',(.82,.81,.775),.93,tex=texture('plaster','plaster',(.865,.852,.815)),bump=.18)
carpet=mat('Greige_loop_carpet',(.40,.362,.31),.95,tex=texture('carpet','carpet',(.435,.40,.348),1024),bump=.55)
wood=mat('Smoked_oak',(.18,.145,.11),.63,tex=texture('oak','wood',(.235,.202,.168)),bump=.1)
consolewood=mat('Console_oak',(.34,.29,.22),.57,tex=texture('console_oak','wood',(.42,.367,.292)),bump=.08)
white=mat('Soft_white_lacquer',(.865,.855,.805),.45)
trim=mat('Soft_white_trim',(.90,.89,.85),.5)
black=mat('Black_polymer',(.018,.021,.024),.43)
rubber=mat('Dark_rubber',(.009,.01,.012),.8)
glass=mat('Screen_glass',(.016,.023,.031),.13,.15)
silver=mat('Brushed_metal',(.34,.35,.34),.28,.7)
fabric=mat('Soundbar_grille',(.035,.037,.04),.88)
linen=mat('Lampshade_linen',(.86,.85,.82),.9)
green=mat('Sage_bedding',(.22,.27,.19),.93)
rose=mat('Bonsai_blossom',(.42,.17,.27),.83)
bark=mat('Bonsai_bark',(.12,.068,.034),.85)
soil=mat('Bonsai_soil',(.055,.036,.022),1)
led=mat('Muted_power_LED',(.12,.27,.38),.3)

def meshUV(obj, scale=1):
    uv = obj.data.uv_layers.active or obj.data.uv_layers.new()
    for p in obj.data.polygons:
        axis = max(range(3),key=lambda i:abs(p.normal[i]))
        for li in p.loop_indices:
            co=obj.data.vertices[obj.data.loops[li].vertex_index].co
            dims=[i for i in range(3) if i!=axis]
            uv.data[li].uv=(co[dims[0]]*scale,co[dims[1]]*scale)

def box(name, loc, dims, material, bevel=.004, rotation=None, texscale=2):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc)
    o=bpy.context.object; o.name=name; o.dimensions=dims
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    meshUV(o,texscale)
    if material:o.data.materials.append(material)
    if bevel:
        b=o.modifiers.new('Soft manufactured edge','BEVEL'); b.width=bevel; b.segments=3
        b.affect='EDGES'
        o.modifiers.new('Corner normals','WEIGHTED_NORMAL')
    if rotation:o.rotation_euler=rotation
    return o

def cyl(name,loc,radius,depth,material,rotation=None,vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=radius,depth=depth,location=loc)
    o=bpy.context.object;o.name=name;o.data.materials.append(material)
    if rotation:o.rotation_euler=rotation
    for p in o.data.polygons:p.use_smooth=True
    b=o.modifiers.new('Edge bevel','BEVEL');b.width=min(.002,depth/5);b.segments=2
    o.modifiers.new('Weighted normal','WEIGHTED_NORMAL')
    return o

def beam(name,a,b,radius,material):
    a,b=Vector(a),Vector(b);d=b-a
    o=cyl(name,(a+b)/2,radius,d.length,material,vertices=12)
    o.rotation_euler=d.to_track_quat('Z','Y').to_euler();return o

def sphere(name,loc,scale,material):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=12,ring_count=8,location=loc)
    o=bpy.context.object;o.name=name;o.scale=scale;o.data.materials.append(material)
    for p in o.data.polygons:p.use_smooth=True
    return o

# Architecture has thickness and openings. The camera remains inside this room.
box('Carpeted_floor',(0,-2.8,-.045),(6.4,5.6,.09),carpet,0,texscale=4)
box('TV_wall',(-.22,.06,1.3),(3.54,.12,2.6),plaster,.003,texscale=4)
box('Doorway_right_pier',(2.78,.06,1.3),(.66,.12,2.6),plaster,.003)
box('Right_door_header',(2,.06,2.36),(.9,.12,.48),plaster,.002)
box('Left_side_wall',(-3.16,-3.55,1.3),(.12,2.5,2.6),plaster,.003)  # open to the kitchen beyond y -2.3
# Right wall with the window the afternoon sun comes through (y -3.2..-0.9, sill 0.7 m, head 2.3 m).
box('Right_side_wall_front',(3.16,-.45,1.3),(.12,.9,2.6),plaster,.003)
box('Right_side_wall_back',(3.16,-4.0,1.3),(.12,1.6,2.6),plaster,.003)
box('Right_window_below',(3.16,-2.05,.35),(.12,2.3,.7),plaster,.002)
box('Right_window_above',(3.16,-2.05,2.45),(.12,2.3,.3),plaster,.002)
box('Right_window_sill',(3.10,-2.05,.71),(.16,2.4,.03),trim,.003)
box('Ceiling',(0,-2.35,2.64),(6.4,6.8,.08),trim,0)
# Flush-mounted light exactly over ShelfLife's initial Room camera position.
start_camera_x=.15+math.sin(-.025)*math.cos(.045)*4.15
start_camera_y=-(.12+math.cos(-.025)*math.cos(.045)*4.15)
cyl('Ceiling_light_mount',(start_camera_x,start_camera_y,2.578),.205,.042,silver,vertices=64)
diffuser=mat('Ceiling_light_opal',(.98,.98,.97),.4)
diffuser.node_tree.nodes.get('Principled BSDF').inputs['Emission Color'].default_value=(1,.94,.81,1)
diffuser.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.8
cyl('Ceiling_light_diffuser',(start_camera_x,start_camera_y,2.542),.192,.032,diffuser,vertices=64)
for x,w in [(-.22,3.54),(2.78,.66)]:
    box('Baseboard',(x,-.031,.057),(w,.048,.114),trim,.004)
    box('Baseboard_cap',(x,-.05,.117),(w,.065,.014),trim,.004)
box('Side_baseboard',(-3.08,-3.55,.057),(.045,2.5,.114),trim,.003)
box('Side_baseboard',(3.08,-2.4,.057),(.045,4.8,.114),trim,.003)
for x in [1.55,2.45]:
    box('Door_casing',(x,-.04,1.075),(.073,.065,2.15),trim,.005)
    box('Door_casing_detail',(x,-.078,1.075),(.023,.017,2.15),trim,.003)
box('Right_door_top_casing',(2,-.04,2.15),(.975,.065,.085),trim,.005)
box('Right_door_top_profile',(2,-.065,2.195),(1.02,.09,.028),trim,.003)
for x in [-1.92,1.49,2.51]:box('Crown_vertical_join',(x,-.025,2.51),(.03,.035,.1),trim,.003)
box('Crown_moulding',(-.22,-.035,2.54),(3.54,.075,.065),trim,.004)
box('Crown_upper',(-.22,-.025,2.585),(3.54,.055,.026),trim,.003)

# Closed six-panel door right of the DVD tower (photos), with knob.
box('Closet_side_backing',(2,.16,1.05),(.9,.08,2.1),plaster,.002)
box('Closed_six_panel_door',(2,.02,1.045),(.8,.042,2.09),trim,.008)
for px in [1.80,2.20]:
    for pz,ph in [(.41,.49),(1.11,.64),(1.77,.40)]:
        box('Door_raised_panel',(px,-.006,pz),(.29,.017,ph),white,.008)
        box('Door_panel_inset',(px,-.018,pz),(.235,.009,ph-.065),trim,.004)
cyl('Door_knob_rosette',(2.32,-.003,.96),.031,.016,silver,(math.pi/2,0,0))
sphere('Door_round_knob',(2.32,-.03,.96),(.029,.024,.029),black)

# Low console: open cubbies, warm white cabinet doors and splayed timber feet.
for x in [-.83,.83]:
    for y in [-.49,-.13]:beam('Console_tapered_foot',(x*1.025,y-.025,.015),(x,y,.19),.027,consolewood)
box('Console_bottom',(0,-.33,.177),(1.92,.53,.035),consolewood,.006)
box('Console_top',(0,-.33,.616),(1.96,.56,.035),consolewood,.007)
box('Console_back',(0,-.075,.39),(1.89,.018,.40),wood,.002)
for x in [-.941,-.34,.34,.941]:box('Console_vertical',(x,-.33,.397),(.026,.51,.408),consolewood,.003)
for x in [-.64,.64]:box('White_console_door',(x,-.604,.392),(.579,.023,.400),white,.005)
box('Console_center_shelf',(0,-.328,.397),(.658,.47,.018),consolewood,.003)
# A small added disc deck on the console end supports the app's disc animation.
box('DVD_player_body',(-.80,-.45,.657),(.245,.24,.044),black,.007)
box('DVD_player_face',(-.80,-.575,.657),(.241,.012,.039),black,.003)
box('DVD_slot',(-.817,-.583,.661),(.166,.003,.004),rubber,.001)
cyl('DVD_power_button',(-.699,-.584,.655),.006,.003,silver,(math.pi/2,0,0),24)
# Reference: broad black handheld on a stand, rear/top vents facing the room.
box('Handheld_rear_body',(-.15,-.35,.493),(.291,.043,.132),black,.022,rotation=(math.radians(-10),0,0))
box('Handheld_rear_inset',(-.15,-.377,.480),(.210,.006,.080),rubber,.010)
for x in [-.271,-.029]: sphere('Handheld_grip',(x,-.360,.474),(.025,.034,.062),black)
box('Handheld_stand_foot',(-.15,-.365,.419),(.065,.099,.012),black,.003)
box('Handheld_stand_upright',(-.15,-.375,.441),(.056,.015,.050),black,.003)
for x in [-.196,-.171,-.146]:
    box('Handheld_top_vent',(x,-.378,.556),(.020,.006,.008),rubber,.003)
    for dx in [-.006,0,.006]:box('Handheld_vent_fin',(x+dx,-.382,.556),(.0015,.002,.006),silver,0)
cyl('Handheld_audio_port',(-.222,-.381,.556),.0027,.002,rubber,(math.pi/2,0,0),16)
box('Handheld_USB_port',(-.209,-.382,.556),(.009,.002,.003),silver,.001)
box('Handheld_power',(-.049,-.380,.556),(.012,.003,.004),silver,.002)
# Tall black mini PC, inset front IO band, blue USB and white indicator.
box('Mini_PC_enclosure',(.222,-.35,.486),(.154,.163,.151),black,.006)
box('Mini_PC_lower_front',(.222,-.436,.464),(.139,.005,.098),rubber,.003)
box('Mini_PC_front_IO_recess',(.222,-.438,.530),(.133,.006,.036),rubber,.002)
box('Mini_PC_front_IO_panel',(.222,-.442,.530),(.120,.003,.026),silver,.001)
box('Mini_PC_USB_black',(.212,-.445,.530),(.018,.002,.006),rubber,.0006)
box('Mini_PC_USB_blue',(.239,-.445,.530),(.012,.002,.005),led,.0005)
cyl('Mini_PC_audio',(.195,-.445,.530),.0028,.002,rubber,(math.pi/2,0,0),16)
cyl('Mini_PC_power',(.271,-.445,.530),.004,.002,black,(math.pi/2,0,0),20)
box('Mini_PC_indicator',(.258,-.445,.531),(.002,.002,.003),white,0)
for i in range(10):box('Mini_PC_side_vent',(.143,-.292-i*.010,.506),(.002,.006,.042),rubber,.0005)
box('Remote_on_shelf',(-.246,-.527,.421),(.160,.044,.014),black,.012)
# Sculpted horizontal PS5: curved white wings around a rounded black core.
box('PS5_black_core',(-.02,-.33,.277),(.54,.32,.101),black,.027)
def ps5_wing(name,z):
    outline=[(-.315,-.17),(.295,-.17),(.280,.17),(-.294,.17)]
    verts=[]
    for dz in [-.004,.004]:
        for x,y in outline:verts.append((x-.02,y-.33,z+dz+(.012 if x<-.2 else -.007)))
    mesh=bpy.data.meshes.new(name);mesh.from_pydata(verts,[],[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]);mesh.materials.append(white)
    obj=bpy.data.objects.new(name,mesh);bpy.context.collection.objects.link(obj)
    mod=obj.modifiers.new('Shell edge','BEVEL');mod.width=.004;mod.segments=3
    obj.modifiers.new('Normals','WEIGHTED_NORMAL')
ps5_wing('PS5_upper_white_wing',.335);ps5_wing('PS5_lower_white_wing',.220)
box('PS5_disc_slot',(-.165,-.503,.234),(.216,.003,.005),rubber,.001)
for i in range(13):box('PS5_front_vent',(.03+i*.013,-.496,.265),(.006,.004,.052),rubber,.001)

# TV has separate front glass, full rear shell, bezel and A-shaped feet.
box('Television_rear_shell',(0,-.422,1.14),(1.51,.075,.862),black,.022)
box('Television_front_bezel',(0,-.467,1.14),(1.51,.021,.862),rubber,.011)
bpy.ops.mesh.primitive_plane_add(size=1,location=(0,-.480,1.14),rotation=(math.pi/2,0,0))
screen=bpy.context.object;screen.name='TVScreen';screen.scale=(1.459,.818,1);screen.data.materials.append(glass)
box('TV_bottom_bezel',(0,-.480,.713),(1.505,.012,.015),black,.004)
for x in [-.56,.56]:
    beam('TV_front_foot',(x,-.43,.725),(x+(.085 if x>0 else -.085),-.56,.638),.012,black)
    beam('TV_rear_foot',(x,-.43,.725),(x,-.30,.638),.009,black)
box('TV_status_LED',(.67,-.489,.715),(.007,.002,.002),led,.0005)
box('Soundbar',(0,-.53,.664),(.94,.097,.070),fabric,.027)
for x in [-.46,.46]:cyl('Soundbar_end',(x,-.53,.664),.027,.012,black,(0,math.pi/2,0))

def shelf(name,x,width,height,depth,bottoms):
    z=-.18
    box(name+'_back',(x,z+depth/2-.009,height/2),(width-.024,.018,height),wood,.003)
    for px in [x-width/2+.009,x+width/2-.009]:box(name+'_side',(px,z,height/2),(.018,depth,height),wood,.003)
    # Boards sit between the side panels (full-width boards shared their end faces with the sides and flickered).
    for y in bottoms+[height-.012]:box(name+'_board',(x,z,y),(width-.036,.29 if name=='Right_media_tower' else depth,.021),wood,.003)
    return z

shelf('Right_media_tower',1.28,.39,2.18,.29,[.069+.26*i for i in range(8)])
shelf('Left_bookcase',-1.43,.45,1.18,.30,[.035,.40,.78])

# A few objects seen in the photo, without any seasonal ornaments.
box('GameCube_silver_cube',(-1.49,-.20,.124),(.164,.164,.114),silver,.007)
box('GameCube_front_plate',(-1.49,-.285,.123),(.158,.008,.103),silver,.004)
for x in [-1.545,-1.509,-1.473,-1.437]:
    cyl('GameCube_port_rim',(x,-.291,.138),.012,.003,black,(math.pi/2,0,0),32)
    cyl('GameCube_port_inset',(x,-.293,.138),.0085,.0015,rubber,(math.pi/2,0,0),24)
    for dx in [-.003,0,.003]:box('GameCube_port_contact',(x+dx,-.294,.138),(.0008,.001,.003),silver,0)
for x in [-1.526,-1.454]:box('GameCube_memory_slot',(x,-.291,.100),(.047,.002,.006),rubber,.001)
cyl('GameCube_round_disc_lid',(-1.49,-.20,.184),.062,.005,black,vertices=64)
cyl('GameCube_center_badge',(-1.49,-.20,.187),.027,.001,silver,vertices=48)
for x,y,r in [(-1.55,-.257,.010),(-1.428,-.257,.013),(-1.55,-.163,.007)]:cyl('GameCube_top_button',(x,y,.185),r,.004,black,vertices=24)
for i in range(8):box('GameCube_side_vent',(-1.573,-.255+i*.015,.129),(.002,.008,.041),rubber,.001)
beam('GameCube_handle_left',(-1.545,-.115,.119),(-1.545,-.09,.119),.009,black)
beam('GameCube_handle_right',(-1.435,-.115,.119),(-1.435,-.09,.119),.009,black)
beam('GameCube_carry_handle',(-1.545,-.09,.119),(-1.435,-.09,.119),.009,black)
box('Controller_dock',(-1.43,-.18,.436),(.19,.13,.04),black,.012)
sphere('Controller_left_grip',(-1.49,-.215,.48),(.045,.045,.025),black)
sphere('Controller_right_grip',(-1.37,-.215,.48),(.045,.045,.025),black)
box('Controller_bridge',(-1.43,-.185,.48),(.12,.055,.036),black,.016)
for x in [-1.47,-1.39]:cyl('Controller_stick',(x,-.18,.503),.009,.006,rubber)

# Muted book spines have thickness, seams and ink bands, all real geometry.
bookmats=[mat('Spine_'+str(i),c,.65) for i,c in enumerate([(.17,.23,.29),(.50,.44,.32),(.30,.12,.10),(.10,.13,.16),(.58,.56,.48),(.23,.29,.23),(.38,.31,.21)])]
def books(prefix,xstart,ybase,count,front,render_only=False):
    for i in range(count):
        h=.181+random.uniform(-.008,.008);w=.012+random.uniform(0,.003)
        x=xstart+i*.015
        o=box(prefix+'_'+str(i),(x,-front+.065,ybase+h/2),(w,.13,h),bookmats[i%len(bookmats)],.001)
        o['render_only']=render_only
        for offset in [.018,h-.022]:
            b=box(prefix+'_ink_'+str(i),(x,-front-.001,ybase+offset),(w*.7,.0015,.003),white,0)
            b['render_only']=render_only

books('Left_DVD',-1.605,.795,22,.337)
books('Left_lower_books',-1.30,.049,6,.33)
# These cases are included only in the Blender review renders. In the runtime
# tower, each shelf is filled with the interactive Plex cases instead.
for row in range(8):books('Render_only_DVD_row'+str(row),1.122,.08+.26*row,21,.336,True)

# Small flowering bonsai on the left shelf, visible in the reference photo.
box('Bonsai_pot',(-1.43,-.17,1.213),(.22,.14,.050),black,.015)
box('Bonsai_soil',(-1.43,-.17,1.241),(.19,.11,.006),soil,.008)
beam('Bonsai_trunk',(-1.43,-.17,1.24),(-1.41,-.16,1.40),.013,bark)
for i in range(12):
    phi=random.random()*math.tau;r=random.uniform(.05,.13)
    end=(-1.43+math.cos(phi)*r,-.17+math.sin(phi)*r*.55,1.39+random.uniform(-.03,.10))
    beam('Bonsai_branch',(-1.41,-.16,1.34),end,.004,bark)
    sphere('Bonsai_blossoms',end,(.037,.026,.025),rose)

# Low-profile wall switch panel by the left bookcase.
box('Wall_switch_plate',(-1.67,-.018,1.49),(.23,.016,.115),trim,.005)
for x in [-1.735,-1.67,-1.605]:
    box('Wall_switch',(x,-.031,1.49),(.023,.010,.047),white,.003)

# Past the TV wall's left corner (photos): the open kitchen floor and the end of the breakfast bar.
vinyl=mat('Dark_vinyl_floor',(.055,.045,.037),.5)
granite=mat('Tan_granite_top',(.40,.31,.21),.35,tex=texture('granite','granite',(.46,.36,.24)))
pine=mat('Pine_stool',(.36,.19,.065),.55)
# Kitchen vinyl reaches into the living room with a diagonal edge from the TV wall's corner.
fv=[(-4.7,.85,.002),(-2.0,.85,.002),(-2.0,-.05,.002),(-2.15,-.5,.002),(-2.3,-1.5,.002),(-2.4,-2.3,.002),(-4.7,-2.3,.002)]
fm=bpy.data.meshes.new('Kitchen_vinyl_floor');fm.from_pydata(fv,[],[list(range(len(fv)))[::-1]])  # counter-clockwise from above, so it faces up
fo=bpy.data.objects.new('Kitchen_vinyl_floor',fm);bpy.context.collection.objects.link(fo);meshUV(fo,2);fm.materials.append(vinyl)
box('Wall_corner_guard',(-1.995,-.005,1.3),(.03,.03,2.6),trim,.002)
# The kitchen's back wall sits just behind the TV wall's plane; a short return closes the corner.
box('Kitchen_corner_return',(-2.04,.47,1.3),(.1,.74,2.6),plaster,.002)
box('Kitchen_back_wall',(-3.39,.9,1.3),(2.7,.1,2.6),plaster,.002)
box('Kitchen_left_wall',(-4.75,-.7,1.3),(.1,3.2,2.6),plaster,.002)
box('Kitchen_ceiling',(-3.98,-.7,2.64),(1.56,3.2,.08),trim,0)
box('Kitchen_switch_plate',(-2.36,.84,1.2),(.08,.012,.12),trim,.003)
box('Kitchen_switch',(-2.36,.832,1.2),(.022,.008,.045),white,.002)
for cx in [-3.32,-4.15]:  # two white bifold closet doors on the back wall
    box('Bifold_door',(cx,.84,1.02),(.76,.03,2.04),trim,.004)
    for dx in [-.19,.19]:
        for pz,ph in [(.55,.7),(1.45,.85)]:
            box('Bifold_panel',(cx+dx,.822,pz),(.28,.012,ph),white,.004)
    box('Bifold_seam',(cx,.822,1.02),(.008,.012,2.0),trim,0)
# Breakfast bar: a peninsula running from near the back wall toward the dining area. White knee
# wall facing the living room and a tan speckled granite top.
box('Breakfast_bar_body',(-2.985,-.875,.53),(.53,2.45,1.06),white,.004)
box('Breakfast_bar_top',(-2.96,-.875,1.078),(.68,2.5,.036),granite,.004)
# Three honey-pine bar stools (photos): thick round seat, four sturdy legs with a slight splay, and
# two levels of rungs, each staggered (front/back rungs sit lower than the side rungs). The first two
# stand close together; the third is apart, at the bar's end. Feet nearly touch the bar.
seat_h=.75
for sy in [-1.05,-.52,.22]:
    sx=-2.50
    cyl('Stool_seat',(sx,sy,seat_h-.019),.165,.038,pine,vertices=28)
    top=[(sx+dx*.105,sy+dy*.105) for dx,dy in [(-1,-1),(1,-1),(1,1),(-1,1)]]
    foot=[(sx+dx*.175,sy+dy*.175) for dx,dy in [(-1,-1),(1,-1),(1,1),(-1,1)]]
    for (fx,fy),(tx,ty) in zip(foot,top): beam('Stool_leg',(fx,fy,0),(tx,ty,seat_h-.038),.019,pine)
    def at(k,z):
        t=z/(seat_h-.038);(fx,fy),(tx,ty)=foot[k],top[k];return (fx+(tx-fx)*t,fy+(ty-fy)*t,z)
    for lo in [.19,.41]:
        # legs 0-1 and 2-3 face the bar/room (front/back); 1-2 and 3-0 are the sides
        for a,b,z in [(0,1,lo),(2,3,lo),(1,2,lo+.045),(3,0,lo+.045)]:
            beam('Stool_rung',at(a,z),at(b,z),.0115,pine)

def light(name,loc,power,color,size,target):
    data=bpy.data.lights.new(name,'AREA');data.energy=power;data.color=color;data.shape='DISK';data.size=size
    obj=bpy.data.objects.new(name,data);bpy.context.collection.objects.link(obj);obj.location=loc
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()
    return obj
# Late afternoon: a low, warm sun from the window on the right throws long shadows leftward across the TV wall;
# the rest of the room is lit by cooler sky light.
sun_data=bpy.data.lights.new('Late_afternoon_sun','SUN');sun_data.energy=12.0;sun_data.color=(1,.70,.42);sun_data.angle=.45  # wide sun = soft, diffused shadow edges
sun=bpy.data.objects.new('Late_afternoon_sun',sun_data);bpy.context.collection.objects.link(sun)
sun.rotation_euler=Vector((-1.0,.7,-.45)).to_track_quat('-Z','Y').to_euler()  # low, from the window on the right
light('Daylight_from_right',(2.5,-2.2,2.2),110,(1,.80,.62),2.5,(0,-.3,.8))
light('Front_soft_fill',(.8,-3.8,2.2),70,(.74,.84,1),3.0,(0,0,1.0))
# Kitchen fluorescent: a point light so it washes the ceiling as well as the counter below.
kl=bpy.data.lights.new('Kitchen_ceiling_light','POINT');kl.energy=220;kl.color=(1,.95,.86);kl.shadow_soft_size=.6
klo=bpy.data.objects.new('Kitchen_ceiling_light',kl);bpy.context.collection.objects.link(klo);klo.location=(-3.9,-.6,2.3)
light('Starting_view_ceiling_light',(start_camera_x,start_camera_y,2.49),35,(1,.98,.95),.38,(start_camera_x,start_camera_y,0))
world=bpy.data.worlds.new('Soft_ambient') if not bpy.data.worlds else bpy.data.worlds[0]
bpy.context.scene.world=world;world.use_nodes=True
world.node_tree.nodes['Background'].inputs[0].default_value=(.55,.62,.76,1)
world.node_tree.nodes['Background'].inputs[1].default_value=.22

def camera(name,loc,target,lens=40):
    data=bpy.data.cameras.new(name);data.lens=lens
    obj=bpy.data.objects.new(name,data);bpy.context.collection.objects.link(obj);obj.location=loc
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()
    return obj
wide=camera('Room_review_camera',(.1,-4.5,1.35),(.1,-.05,1.08),39)
side=camera('Side_review_camera',(-1.8,-3.6,1.65),(.35,-.06,1.02),36)

# Export evaluated, triangulated meshes with UVs, normals and material colors.
# COLLADA remains supported by SceneKit; avoiding an image plane is intentional.
def export_dae(path):
    ns='http://www.collada.org/2005/11/COLLADASchema'
    root=Element('COLLADA',xmlns=ns,version='1.4.1')
    asset=SubElement(root,'asset')
    SubElement(asset,'created').text='2026-10-21T18:00:00Z'
    SubElement(asset,'modified').text='2026-10-21T18:00:00Z'
    SubElement(asset,'unit',name='meter',meter='1');SubElement(asset,'up_axis').text='Y_UP'
    images=SubElement(root,'library_images');effects=SubElement(root,'library_effects');materials=SubElement(root,'library_materials')
    for m in bpy.data.materials:
        mid=m.name.replace(' ','_')
        effect=SubElement(effects,'effect',id=mid+'-fx');profile=SubElement(effect,'profile_COMMON')
        nodes=[n for n in m.node_tree.nodes if n.type=='TEX_IMAGE'] if m.use_nodes else []
        if nodes:
            im=nodes[0].image; iid=mid+'-image'
            SubElement(SubElement(images,'image',id=iid),'init_from').text=os.path.basename(im.filepath_raw)
            surface=SubElement(SubElement(profile,'newparam',sid=mid+'-surface'),'surface',type='2D')
            SubElement(surface,'init_from').text=iid
            sampler=SubElement(SubElement(profile,'newparam',sid=mid+'-sampler'),'sampler2D')
            SubElement(sampler,'source').text=mid+'-surface'
        technique=SubElement(profile,'technique',sid='common');phong=SubElement(technique,'phong')
        SubElement(SubElement(phong,'ambient'),'color').text='0.15 0.15 0.15 1'
        diffuse=SubElement(phong,'diffuse')
        if nodes:SubElement(diffuse,'texture',texture=mid+'-sampler',texcoord='UVSET0')
        else:SubElement(diffuse,'color').text=' '.join(str(v) for v in m.diffuse_color)
        rough=m.node_tree.nodes.get('Principled BSDF').inputs['Roughness'].default_value
        spec=max(.02,(1-rough)*.35)
        SubElement(SubElement(phong,'specular'),'color').text=f'{spec} {spec} {spec} 1'
        SubElement(SubElement(phong,'shininess'),'float').text=str(5+(1-rough)*70)
        mm=SubElement(materials,'material',id=mid,name=mid);SubElement(mm,'instance_effect',url='#'+mid+'-fx')
    geos=SubElement(root,'library_geometries');visual=SubElement(SubElement(root,'library_visual_scenes'),'visual_scene',id='LivingRoom',name='LivingRoom')
    dg=bpy.context.evaluated_depsgraph_get();count=0;tri_count=0
    def source(mesh,id,values,stride,params):
        s=SubElement(mesh,'source',id=id);SubElement(s,'float_array',id=id+'-array',count=str(len(values))).text=' '.join(f'{v:.6g}' for v in values)
        a=SubElement(SubElement(s,'technique_common'),'accessor',source='#'+id+'-array',count=str(len(values)//stride),stride=str(stride))
        for p in params:SubElement(a,'param',name=p,type='float')
    for obj in list(bpy.data.objects):
        if obj.type!='MESH' or obj.get('render_only'):continue
        ev=obj.evaluated_get(dg);me=ev.to_mesh();me.calc_loop_triangles()
        gid='mesh_'+str(count);count+=1
        mesh=SubElement(SubElement(geos,'geometry',id=gid,name=obj.name),'mesh')
        positions=[];normals=[];uvs=[];triangles={}
        uv=me.uv_layers.active
        normalmatrix=obj.matrix_world.to_3x3().inverted().transposed()
        for tri in me.loop_triangles:
            indices=[]
            for li in tri.loops:
                loop=me.loops[li];v=obj.matrix_world@me.vertices[loop.vertex_index].co
                n=normalmatrix@me.corner_normals[li].vector;n.normalize()
                index=len(positions)//3;indices.append(index)
                positions.extend((v.x,v.z,-v.y));normals.extend((n.x,n.z,-n.y))
                u=uv.data[li].uv if uv else (0,0);uvs.extend(u)
            triangles.setdefault(tri.material_index,[]).extend(indices);tri_count+=1
        source(mesh,gid+'-pos',positions,3,['X','Y','Z']);source(mesh,gid+'-norm',normals,3,['X','Y','Z']);source(mesh,gid+'-uv',uvs,2,['S','T'])
        v=SubElement(mesh,'vertices',id=gid+'-verts');SubElement(v,'input',semantic='POSITION',source='#'+gid+'-pos')
        node=SubElement(visual,'node',id='node_'+str(count),name=obj.name,type='NODE')
        instance=SubElement(node,'instance_geometry',url='#'+gid)
        bindings=SubElement(SubElement(instance,'bind_material'),'technique_common')
        for mi,indices in triangles.items():
            material=me.materials[mi].name.replace(' ','_') if len(me.materials)>mi and me.materials[mi] else 'Soft_white_plaster'
            ts=SubElement(mesh,'triangles',material=material,count=str(len(indices)//3))
            SubElement(ts,'input',semantic='VERTEX',source='#'+gid+'-verts',offset='0')
            SubElement(ts,'input',semantic='NORMAL',source='#'+gid+'-norm',offset='0')
            SubElement(ts,'input',semantic='TEXCOORD',source='#'+gid+'-uv',offset='0',set='0')
            SubElement(ts,'p').text=' '.join(str(i) for i in indices)
            im=SubElement(bindings,'instance_material',symbol=material,target='#'+material)
            SubElement(im,'bind_vertex_input',semantic='UVSET0',input_semantic='TEXCOORD',input_set='0')
        ev.to_mesh_clear()
    SubElement(SubElement(root,'scene'),'instance_visual_scene',url='#LivingRoom')
    ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)
    print(f'EXPORTED {count} mesh objects, {tri_count} triangles to {path}',flush=True)

export_dae(os.path.join(ASSETS,'room.dae'))

def export_glb(path):
    """Same room for the Android TV app (Filament loads glTF). Blender's exporter applies the
    same Z-up to Y-up mapping as export_dae."""
    bpy.ops.object.select_all(action='DESELECT')
    for o in bpy.data.objects:
        if o.type=='MESH' and not o.get('render_only'): o.select_set(True)
    os.makedirs(os.path.dirname(path),exist_ok=True)
    bpy.ops.export_scene.gltf(filepath=path,export_format='GLB',use_selection=True,export_apply=True,export_yup=True,
                              export_lights=False,export_cameras=False,export_image_format='JPEG')
    print(f'EXPORTED_GLB {path} {os.path.getsize(path)} bytes',flush=True)

export_glb(os.path.join(ROOT,'android/app/src/main/assets/room.glb'))
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=24
scene.cycles.use_denoising=True
scene.render.resolution_x=1600;scene.render.resolution_y=1000;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.view_settings.view_transform='AgX'
scene.camera=wide
for im in bpy.data.images:
    if im.filepath_raw: im.pack()
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUTPUT,'LivingRoom.blend'))
for cam,name in ([] if '--export-only' in sys.argv else [(wide,'room-wide.png'),(side,'room-side.png')]):
    scene.camera=cam;scene.render.filepath=os.path.join(OUTPUT,name)
    bpy.ops.render.render(write_still=True)
print('ROOM_BUILD_COMPLETE',flush=True)
