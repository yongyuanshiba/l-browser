window.addEventListener('error', function (event) {
    console.log('L-HOME-ERROR: ' + event.message);
});
window.addEventListener('load', function () {
    var line = 'LPROBE href=' + location.href + ' origin=' + location.origin;
    try {
        var prev = window.localStorage.getItem('__l_probe__');
        window.localStorage.setItem('__l_probe__', 'probe-ok');
        line += ' ls_prev=' + prev + ' ls_read=' + window.localStorage.getItem('__l_probe__');
    } catch (e) {
        line += ' ls_error=' + e.name;
    }
    document.getElementById('out').textContent = line;
    console.log(line);
});
