#!/usr/bin/env python3
"""Render Freya's private film from timed narration and the real guide screenshots."""
import argparse, json, math, subprocess, sys
from pathlib import Path
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from freya_film import OUT, manifest

ROOT=Path(__file__).resolve().parents[2]
FPS=15; W=480; H=360
FONT=ROOT/'java/android/assets/fonts/Inter-Bold.ttf'
GOLD=(255,221,109); INK=(15,11,32); LILAC=(207,181,255)
def font(n): return ImageFont.truetype(str(FONT), n)
def duration(p):
    return float(subprocess.check_output(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0',str(p)],text=True))
def cue(beat, phrase, fallback):
    p=OUT/'audio'/(beat+'.words.json')
    if not p.exists(): return fallback
    words=[w for s in json.loads(p.read_text()) for w in s['words']]
    needle=phrase.lower().split()
    tokens=[''.join(c for c in w['word'].lower() if c.isalnum()) for w in words]
    for i in range(len(words)-len(needle)+1):
        if tokens[i:i+len(needle)]==needle: return words[i]['start']
    return fallback

def wrap(draw,text,f,maxwidth):
    lines=[]; line=''
    for word in text.split():
        candidate=(line+' '+word).strip()
        if draw.textlength(candidate,font=f)>maxwidth and line: lines.append(line);line=word
        else: line=candidate
    if line:lines.append(line)
    return lines

def background(t,quiet=False):
    yy,xx=np.mgrid[0:H,0:W]
    glow=np.exp(-((xx-350)**2+(yy-110)**2)/50000)
    arr=np.zeros((H,W,3),dtype=np.uint8)
    for i,(a,b) in enumerate([(15,28),(11,14),(32,48)]):arr[:,:,i]=a+glow*b
    im=Image.fromarray(arr); d=ImageDraw.Draw(im)
    for k in range(28):
        x=(k*97+19)%W; y=(k*53+37)%H
        a=(math.sin(t*(.7+k%3*.2)+k)*.5+.5)
        v=int(80+100*a)
        d.ellipse((x-1,y-1,x+1,y+1),fill=(v,int(v*.8),int(v*.6)))
    return im

def header(im,title,n,t):
    d=ImageDraw.Draw(im)
    d.text((14,9),f'{n:02d}',font=font(13),fill=GOLD)
    f=font(13); title=title.upper()
    while d.textlength(title,font=f)>410:f=font(f.size-1)
    d.text((42,9),title,font=f,fill=(246,239,255))
    d.line((14,29,466,29),fill=(74,55,100),width=1)

def pill(im,text,y=316):
    d=ImageDraw.Draw(im);f=font(14); lines=wrap(d,text,f,430)
    if len(lines)>2:f=font(12);lines=wrap(d,text,f,430)
    h=12+len(lines)*18
    d.rounded_rectangle((12,y-h+28,468,y+28),radius=9,fill=(25,17,47),outline=(142,104,182),width=1)
    for i,line in enumerate(lines):d.text((240,y-h+34+i*18),line,anchor='mt',font=f,fill=GOLD)

def pulse(im,x,y,t,active=True,r=16):
    d=ImageDraw.Draw(im)
    if active:
        a=(math.sin(t*7)+1)/2
        rr=r+int(a*4)
        d.ellipse((x-rr,y-rr,x+rr,y+rr),outline=GOLD,width=3)
        d.ellipse((x-4,y-4,x+4,y+4),fill=GOLD)

DEVICE=Image.open('/Users/cns/Downloads/s-l1600.webp').convert('RGB').crop((175,42,625,758))
SHOTS={}
HAND_FRAMES=None
def hand_frames():
    global HAND_FRAMES
    if HAND_FRAMES is None:
        HAND_FRAMES=[];cap=cv2.VideoCapture(str(OUT/'video/hands.mp4'))
        while True:
            ok,frame=cap.read()
            if not ok:break
            HAND_FRAMES.append(Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)))
        cap.release()
    return HAND_FRAMES
def shot(name):
    if name not in SHOTS:SHOTS[name]=Image.open(ROOT/'docs/screenshots'/name).convert('RGB')
    return SHOTS[name]

def device_scene(beat,t,dur,title,n):
    im=background(t);header(im,title,n,t)
    # Product photo is used as video artwork; the stock display is replaced by the real app.
    dev=DEVICE.resize((175,278),Image.Resampling.LANCZOS)
    im.paste(dev,(24,44))
    screen='01-menu.png'
    if beat['id']=='heart':screen='03-now-playing.png'
    if beat['id']=='voice' and t>cue('voice','press the middle',7):screen='19-voice-listening.png'
    im.paste(shot(screen).resize((141,104),Image.Resampling.LANCZOS),(36,63))
    d=ImageDraw.Draw(im)
    x,y=111,246
    text='';button=None;hold_start=None
    bid=beat['id']
    if bid=='wheel':
        if t<cue(bid,'press the yellow',6.5):
            a=t*2;px=x+34*math.cos(a);py=y+34*math.sin(a)
            pulse(im,px,py,t,r=10);text='SLIDE AROUND\nTHE WHITE RING'
        elif t<cue(bid,'the button at',10):button=(x,y);text='PRESS THE\nYELLOW MIDDLE'
        else:button=(x,y-33);text='TOP BUTTON\nMENU = BACK'
    elif bid=='play':
        if t<cue(bid,'right skips',2):button=(x,y+33);text='BOTTOM BUTTON\nPLAY / PAUSE'
        elif t<cue(bid,'when music',5):button=(x+33,y);text='RIGHT = NEXT\nLEFT = PREVIOUS'
        elif t<cue(bid,'hold menu',10):
            a=t*2;pulse(im,x+34*math.cos(a),y+34*math.sin(a),t,r=10);text='NOW PLAYING?\nWHEEL = VOLUME'
        else:button=(x,y-33);hold_start=cue(bid,'hold menu',10)+.5;text='PRESS AND HOLD\nMENU'
    elif bid in ('heart','voice'):
        start=cue(bid,'press the middle',5.5)
        if t<start:text='ON A SONG' if bid=='heart' else 'START ON\nTHE MAIN MENU'
        else:
            button=(x,y);hold_start=start+.5
            text=('KEEP HOLDING…' if t<hold_start+2.3 else '♥  FAVOURITED!') if bid=='heart' else 'KEEP HOLDING\nWHILE YOU SPEAK'
            if bid=='voice' and t>cue(bid,'now let go',12.5):button=None;text='LET GO\nSEARCHING…';screen='20-voice-thinking.png';im.paste(shot(screen).resize((141,104)),(36,63))
    if button:pulse(im,*button,t,r=16)
    if hold_start is not None:
        elapsed=max(0,t-hold_start);progress=min(1,elapsed/2.3)
        if button:
            bx,by=button;d.arc((bx-23,by-23,bx+23,by+23),-90,-90+progress*359,fill=(255,130,194),width=4)
        if elapsed<2.3 and elapsed>0:
            d.text((330,228),'KEEP HOLDING',anchor='mm',font=font(14),fill=GOLD)
            d.rounded_rectangle((225,245,437,257),radius=6,fill=(56,38,77))
            d.rounded_rectangle((225,245,225+max(6,212*progress),257),radius=6,fill=GOLD)
    for i,line in enumerate(text.split('\n')):d.text((330,106+i*26),line,anchor='mm',font=font(18),fill=GOLD if i==0 else LILAC)
    if bid=='voice':
        say=cue(bid,'shake it off',11)
        if say-1<t<say+2.5:d.text((330,190),'“Shake It Off!”',anchor='mm',font=font(20),fill=(255,152,198))
    # Use only the lower controls from the generated hand demonstration: the AI screen is excluded.
    if bid in ('heart','voice') and (OUT/'video/hands.mp4').exists():
        start=cue(bid,'press the middle',5 if bid=='heart' else 7.4)
        release=cue(bid,'let go',10.8) if bid=='heart' else cue(bid,'now let go',13.7)
        if start<=t<release:
            frames=hand_frames()
            local=2.4+min(4.4,(t-start)*.8)
            idx=min(len(frames)-1,round(local*len(frames)/8))
            detail=frames[idx].crop((190,245,550,365)).resize((456,180),Image.Resampling.LANCZOS)
            im=background(t);header(im,title,n,t);im.paste(detail,(12,85));d=ImageDraw.Draw(im)
            elapsed=max(0,t-start)
            d.rounded_rectangle((165,50,315,78),radius=8,fill=INK)
            d.text((240,64),'KEEP HOLDING…',anchor='mm',font=font(15),fill=GOLD)
    pill(im,beat['label']);return im

SCREEN_SEQUENCES={
 'music':[(0,'10-music.png'),(.55,'14-artists.png'),(.82,'13-new-songs.png')],
 'albums':[(0,'14-artists.png'),(.13,'15-artist.png'),(.45,'17-album-tracks.png'),(.68,'16-artist-songs.png')],
 'shuffle':[(0,'10-music.png'),(.25,'16-artist-songs.png'),(.52,'17-album-tracks.png'),(.68,'03-now-playing.png')],
 'books':[(0,'06-authors.png'),(.17,'07-books.png'),(.38,'08-book-page.png'),(.7,'05-book-now-playing.png')],
 'chapters':[(0,'08-book-page.png'),(.16,'09-chapters.png'),(.48,'05-book-now-playing.png')],
 'typing':[(0,'18-search.png'),(.8,'21-voice-results.png')],
 'results':[(0,'21-voice-results.png'),(.75,'03-now-playing.png')],
 'settings':[(0,'24-settings.png'),(.22,'26-wifi.png'),(.7,'24-settings.png')],
 'help':[(0,'02-menu-more.png'),(.15,'29-help.png')],
}
def screen_scene(beat,t,dur,title,n):
    im=background(t);header(im,title,n,t)
    source=beat['visual'];progress=t/dur
    for frac,name in SCREEN_SEQUENCES.get(beat['id'],[]):
        if progress>=frac:source=name
    # 400x300 retains every pixel region of the actual 4:3 screenshot, including its mini player.
    s=shot(source).resize((400,300),Image.Resampling.LANCZOS)
    im.paste(s,(40,40));d=ImageDraw.Draw(im)
    d.rounded_rectangle((38,38,441,341),radius=4,outline=(111,82,147),width=2)
    # A moving gold marker and gentle highlight call attention without inventing interface text.
    marker_y={'10-music.png':206,'14-artists.png':103,'15-artist.png':167,'17-album-tracks.png':94,
              '12-favourites.png':104,'07-books.png':99,'09-chapters.png':120,'18-search.png':179,
              '21-voice-results.png':93,'23-listen-on.png':109,'27-bluetooth.png':111,'24-settings.png':95,
              '29-help.png':93,'01-menu.png':215,'02-menu-more.png':270}.get(source)
    if marker_y and t>1.5:
        xx=28+int(2*math.sin(t*4));d.polygon([(xx-7,marker_y-6),(xx+3,marker_y),(xx-7,marker_y+6)],fill=GOLD)
    if beat['id']=='unlock':
        a=cue('unlock','one two three',9.5)
        if a-.5<t<a+2.0:
            num=min(3,1+int(max(0,t-a)*3))
            d.rounded_rectangle((130,110,350,175),radius=14,fill=INK,outline=GOLD,width=2)
            d.text((240,142),'  '.join(str(i) for i in range(1,num+1)),anchor='mm',font=font(34),fill=GOLD)
    # Display the reminder briefly, then leave the screenshot unobscured.
    if t<3.6 or t>dur-3.1:pill(im,beat['label'])
    return im

def render(beat,title,n):
    dst=OUT/'segments'/(beat['id']+'.mp4');audio=OUT/'audio'/(beat['id']+'.wav')
    if dst.exists():return
    dur=duration(audio)+.5;frames=math.ceil(dur*FPS)
    cmd=['ffmpeg','-y','-loglevel','error','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','-',
         '-i',str(audio),'-af','apad,loudnorm=I=-18:TP=-2:LRA=7','-t',str(frames/FPS),'-c:v','libx264','-preset','fast',
         '-profile:v','baseline','-level','3.0','-pix_fmt','yuv420p','-g','15','-crf','19','-c:a','aac','-b:a','128k','-ar','44100','-ac','2','-movflags','+faststart',str(dst)]
    proc=subprocess.Popen(cmd,stdin=subprocess.PIPE)
    try:
        for f in range(frames):
            t=f/FPS
            im=(device_scene if beat['kind'] in ('hands','device') else screen_scene)(beat,t,dur,title,n)
            if t<.25:im=Image.blend(Image.new('RGB',(W,H),INK),im,t/.25)
            proc.stdin.write(im.tobytes())
    finally:proc.stdin.close()
    if proc.wait():raise RuntimeError('ffmpeg failed '+beat['id'])
    print('rendered',beat['id'],round(frames/FPS,2),flush=True)

if __name__=='__main__':
    for c in manifest()['chapters']:
        for b in c['beats']:
            if b['kind'] in ('screen','device','hands'):render(b,c['title'],c['number'])
