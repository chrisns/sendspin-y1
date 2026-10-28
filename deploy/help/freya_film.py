#!/usr/bin/env python3
"""Personal birthday-film production manifest. Generated/private media stay in out/."""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / 'out' / 'freya'
STYLE = ('Perform only the dialogue after DIALOGUE. Use the same original young adult female '
         'North American voice throughout: warm, bright, expressive, conversational, a little cheeky. '
         'Not an imitation of a real singer. Clear diction for a nine-year-old learning a device. '
         'Freya is pronounced FRAY-uh. Natural short pauses between instructions; smile at jokes. '
         'Do not say the directions. DIALOGUE: ')

# Each beat has its own audio: visual demonstrations follow real speech, not estimated word counts.
CHAPTERS = [
 ('Happy birthday, Freya!', [
  ('intro', 'avatar', "It's me, hi! Oh my gosh, Freya! It's your birthday! Nine! I am so excited to be the first thing you hear on your very own iPod. Oh, we are going to have so much fun. Get comfy. Your daddy asked this AI version of me to show you around what he's built, just for you. You could say it's Taylor-made! Heh-heh! Your music. Your stories. Your very own little world. Ready? Let's make the whole place shimmer.", 'intro', 'YOUR BIRTHDAY ERA'),
  ('welcome', 'screen', "Your music and downloaded audiobooks live right here, so they can come on adventures even without Wi-Fi. This little yellow superstar has a Help menu, too. You can watch everything, or jump straight to the bit you need.", '01-menu.png', 'YOUR MUSIC. YOUR STORIES.')]),
 ('Finding your way around', [
  ('wheel', 'device', "First, the wheel. Slide your finger around the white ring to move through a list. Press the yellow middle button to choose. Turn, choose. Easy! The button at the top is Menu. Give it a quick press to go back.", 'wheel', 'TURN THE WHEEL · PRESS TO CHOOSE'),
  ('play', 'device', "The bottom button plays or pauses. Right skips forward, left goes back. When music is playing, turn the wheel to change the volume. Hold Menu for a moment to open the power menu. A quick press and a hold do different things.", 'menu', 'TAP = BACK · HOLD = POWER MENU'),
  ('unlock', 'screen', "After a quiet minute, the buttons lock so your pocket cannot DJ. To wake them up, press the same button three times, quickly. One, two, three! There. You're out of the woods.", '31-hold.png', 'SAME BUTTON · 3 QUICK PRESSES')]),
 ('Music: your own little world', [
  ('music', 'screen', "Choose Music. You can explore by artist, find a kind of music, or see what just arrived in New songs. Think of it as your own little concert, with absolutely no queue for the toilets.", '10-music.png', 'MUSIC → ARTISTS'),
  ('albums', 'screen', "Choose an artist, then turn the wheel along the album covers. Pick an album to see its songs. Or choose All songs for everything by that artist. A whole collection of different eras. I approve.", '15-artist.png', 'ARTIST → ALBUM → SONG'),
  ('shuffle', 'screen', "Feeling Fearless? Shuffle all music mixes everything into a surprise order. Inside an album or an artist's All songs, Shuffle mixes just those songs. Pick a song to play. If it has timed lyrics, the words follow along. Bedroom microphone: entirely optional.", '17-album-tracks.png', 'SHUFFLE = A SURPRISE ORDER')]),
 ('Favourites: give it a heart', [
  ('heart', 'hands', "Found a song you love? Highlight the song, or open its Now Playing screen. Press the middle button and keep it down for a moment. Keep holding... and there's your heart! Let go. A tiny Love Story, right on your iPod.", 'heart', 'HOLD THE MIDDLE → ♥'),
  ('favourites', 'screen', "Those hearts live together in Favourites, near the top of Music. Hold the middle button on that song again to take its heart away. So you can change your mind. We call that character development.", '12-favourites.png', 'MUSIC → FAVOURITES')]),
 ('Audiobooks: the next chapter', [
  ('books', 'screen', "Your audiobooks are in Audiobooks. Choose an author, then a book, and press Play. Downloaded books work away from Wi-Fi, too. When you stop, your iPod remembers your place, even if you switch it off. This story has a very good memory.", '07-books.png', 'AUDIOBOOKS → CHOOSE A BOOK'),
  ('chapters', 'screen', "If a book has chapters, choose Chapters to jump to one. The next and previous buttons move between chapters. Hold either one to skip thirty seconds forward or back. Missed something brilliant? Rewind. Long Story Short, you are in charge of the next chapter.", '09-chapters.png', 'HOLD NEXT / PREVIOUS → 30 SECONDS')]),
 ('Search: say the magic words', [
  ('typing', 'screen', "Looking for something? Choose Search. Turn the wheel to find a letter, press the middle to add it, and press Play when you have finished spelling. You can search for a song, an album, or an artist.", '18-search.png', 'WHEEL = LETTER · MIDDLE = ADD · PLAY = SEARCH'),
  ('voice', 'hands', "Or, here's the magic bit. Go back to the main menu, away from a song and its Now Playing screen. Press the middle button and keep holding while you speak. Ask for... Shake It Off! Now let go, and give it a moment to find the words.", 'voice', 'HOLD → SPEAK → LET GO'),
  ('dance', 'dance', "", 'dance', 'SHAKE IT OFF!'),
  ('results', 'screen', "Sorry. Extremely serious product demonstration. Songs already on your iPod come first. With Wi-Fi, you can also find more to stream. Add one to your library if you want it saved here after the next sync. One button, two tricks: on a song, hold for a heart. On the menu, hold to talk.", '21-voice-results.png', 'ON A SONG: ♥ · ON THE MENU: TALK')]),
 ('Speakers and headphones', [
  ('speakers', 'screen', "Want your room to become the stage? Choose Listen on, then choose a speaker. Your music or story carries on from the same place. You can bring it back to the iPod, too. A very small world tour.", '23-listen-on.png', 'LISTEN ON → CHOOSE A SPEAKER'),
  ('headphones', 'screen', "For Bluetooth headphones, open Settings, then Bluetooth headphones. Put the headphones into pairing mode, choose Search for headphones, and pick yours. The wheel changes the volume while music plays. Headphone buttons work too.", '27-bluetooth.png', 'SETTINGS → BLUETOOTH HEADPHONES'),
  ('brothers', 'screen', "A little backstage tip for living with Oliver and Noah: when Noah's around, headphones keep your concert just for you. When he's out, your room can become the dance floor! Turn it up to a comfy level, superstar. Big birthday energy; happy ears for everyone.", '03-now-playing.png', 'HEADPHONES ON. YOUR OWN PRIVATE CONCERT.')]),
 ('Bedtime: sweet dreams', [
  ('bedtime', 'screen', "Even superstars need sleep. At bedtime, you have a little listening time. When it is used up, your iPod gently fades out and says goodnight. Time for your Wildest Dreams. If Mummy or Daddy says a little extra is okay, they can unlock it with their special code.", '33-bedtime-lock.png', 'A LITTLE LISTEN. THEN SWEET DREAMS.')]),
 ('Finding your iPod', [
  ('finding', 'screen', "If your iPod disappears under a cushion, do not enter your dramatic detective era just yet. A grown-up can make it ring from their phone when it is connected at home. Follow the sound, then press any button to stop it. The lock screen can show who to call if someone finds it, too.", '31-hold.png', 'LOST IT? ASK A GROWN-UP TO RING IT.')]),
 ('Settings: make it yours', [
  ('settings', 'screen', "Settings is where you can join Wi-Fi, change the screen brightness, and choose the wheel clicks. Some settings need a grown-up's code. Joining Wi-Fi is fine for you; removing a network needs Mummy or Daddy. Your iPod, your style. Obviously, we take style very seriously around here.", '24-settings.png', 'SETTINGS → MAKE IT YOURS'),
  ('help', 'screen', "And remember Help, at the bottom of the menu. Come back any time. During this video, Next and Previous jump between chapters, the middle button pauses, and Menu takes you back. No need to remember everything on the first go.", '29-help.png', 'HELP IS ALWAYS HERE')]),
 ('Nine looks good on you', [
  ('outro', 'avatar', "Freya, thank you for watching. Happy ninth birthday! Nine looks so good on you. You're growing into such a wonderful person, and Mummy and Daddy are so proud of you. This little iPod was made with a whole lot of love, just for you. I hope today is full of your favourite people, brilliant surprises, and a truly excellent slice of cake. And I hope all the days after it have a little birthday sparkle, too. Keep being curious. Keep being kind. Keep being wonderfully you. Your next era starts now, Freya. We never go out of style. Happy birthday!", 'outro', 'HAPPY 9TH BIRTHDAY, FREYA!'),
  ('faces', 'faces', "", 'faces', 'ONE LAST THING…')])]

def manifest():
    chapters = []
    for i, (title, beats) in enumerate(CHAPTERS):
        chapters.append({'number': i+1, 'title': title, 'beats': [
            dict(id=b[0], kind=b[1], text=b[2], visual=b[3], label=b[4]) for b in beats]})
    return {'recipient':'Freya', 'age':9, 'size':[480,360], 'fps':15,
            'disclosure':'AI birthday fantasy · fictional performance', 'chapters':chapters}

if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/'manifest.json').write_text(json.dumps(manifest(), indent=2, ensure_ascii=False)+'\n')
    print(OUT/'manifest.json')
