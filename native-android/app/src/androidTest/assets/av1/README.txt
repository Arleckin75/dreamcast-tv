Generated test media: 30 seconds of FFmpeg testsrc2, AV1 1280x720 at 5 fps, no audio.
No third-party footage. Used only by the software instrumentation test.

Reproduce in this directory using an FFmpeg build with libaom-av1:
ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=5 -t 30 -an -c:v libaom-av1 -cpu-used 8 -crf 55 -b:v 0 -g 20 -pix_fmt yuv420p -threads 4 -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_type fmp4 -hls_segment_filename segment%d.m4s media.m3u8
