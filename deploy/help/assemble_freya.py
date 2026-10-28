#!/usr/bin/env python3
"""Assemble reviewed host shots and screenshot demonstrations, with exact chapter times."""
import json, subprocess
from pathlib import Path
from freya_film import OUT, manifest
from render_freya import duration

def run(args): subprocess.run(['ffmpeg','-y','-loglevel','error',*args],check=True)
def normalise(name, audio=None, seconds=None):
    src=OUT/'video'/(name+'.mp4');dst=OUT/'segments'/(name+'.mp4')
    args=['-i',str(src)]
    if audio:args+=['-i',str(audio),'-map','0:v:0','-map','1:a:0']
    vf='scale=480:360:force_original_aspect_ratio=increase,crop=480:360,setsar=1,fps=15'
    if name in ('intro','outro'):
        # Cuts to a tighter angle give the greeting and punchline their own visual beat.
        vf+=",zoompan=z='if(between(on,39,94)+between(on,340,379),1.08,1)':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=1:s=480x360:fps=15"
    # The label remains readable on the actual small device.
    vf+=",drawbox=x=4:y=4:w=98:h=17:color=black@0.65:t=fill,drawtext=fontfile=java/android/assets/fonts/Inter-Bold.ttf:text='AI birthday fantasy':fontsize=9:fontcolor=white:x=8:y=8"
    if name in ('intro','outro'):
        vf+=",drawbox=x=0:y=315:w=480:h=45:color=0x140c25@0.8:t=fill:enable='lt(t,6)',drawtext=fontfile=java/android/assets/fonts/Inter-Bold.ttf:text='HAPPY 9TH BIRTHDAY, FREYA!':fontsize=19:fontcolor=0xffdd6d:x=(w-tw)/2:y=329:enable='lt(t,6)'"
    if name=='faces':
        vf+=",drawbox=x=120:y=317:w=240:h=30:color=black@0.7:t=fill:enable='lt(t,1.2)',drawtext=fontfile=java/android/assets/fonts/Inter-Bold.ttf:text='P.S. One last thing...':fontsize=17:fontcolor=0xffdd6d:x=(w-tw)/2:y=324:enable='lt(t,1.2)'"
    args+=['-vf',vf,'-af','apad,loudnorm=I=-18:TP=-2:LRA=7']
    if seconds:args+=['-t',str(seconds)]
    args+=['-c:v','libx264','-preset','fast','-crf','19','-profile:v','baseline','-level','3.0','-pix_fmt','yuv420p','-r','15','-g','15','-c:a','aac','-b:a','128k','-ar','44100','-ac','2','-movflags','+faststart',str(dst)]
    run(args)

if __name__=='__main__':
    for name in ('intro','outro'):
        normalise(name,OUT/'audio'/(name+'.wav'),duration(OUT/'audio'/(name+'.wav')))
    normalise('dance',OUT/'audio/dance.wav',8)
    normalise('faces',OUT/'audio/faces.wav',5)
    parts=[];chapters=[];t=0
    for c in manifest()['chapters']:
        chapters.append({'title':c['title'],'start':round(t,3)})
        for b in c['beats']:
            p=OUT/'segments'/(b['id']+'.mp4')
            if not p.exists():raise SystemExit('Missing '+str(p))
            parts.append(p);t+=duration(p)
    (OUT/'film-list.txt').write_text(''.join("file '"+str(p)+"'\n" for p in parts))
    meta=[';FFMETADATA1','title=Freya — Your Birthday Era','comment=Private AI birthday fantasy; original synthetic voice; real device screenshots.']
    for i,c in enumerate(chapters):
        end=chapters[i+1]['start'] if i+1<len(chapters) else t
        meta+=['[CHAPTER]','TIMEBASE=1/1000',f'START={round(c["start"]*1000)}',f'END={round(end*1000)}','title='+c['title']]
    (OUT/'chapters.ffmeta').write_text('\n'.join(meta)+'\n')
    run(['-f','concat','-safe','0','-i',str(OUT/'film-list.txt'),'-i',str(OUT/'chapters.ffmeta'),'-map_metadata','1','-map_chapters','1','-c','copy','-movflags','+faststart',str(OUT/'help.mp4')])
    (OUT/'help.json').write_text(json.dumps({'chapters':chapters},indent=2)+'\n')
    print('Film:',OUT/'help.mp4',round(t,2),'seconds',len(chapters),'chapters')
