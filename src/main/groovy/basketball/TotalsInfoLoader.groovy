package basketball

@Grab(group = 'net.sourceforge.nekohtml', module = 'nekohtml', version = '1.9.14')
@Grab(group = 'com.googlecode.soundlibs', module = 'jlayer', version = '1.0.1-1')
import basketball.actions.PariMatchLiveScanner

import java.time.LocalDate

import static java.time.format.DateTimeFormatter.ofPattern

path = '/todayTest.html'
def scanningMillisecondsInterval = 30_000

println('TODAY IS ' + LocalDate.now().getDayOfWeek() + ' ' + LocalDate.now().format(ofPattern('dd/MM/yyyy')))
Timer timer = new Timer();
timer.schedule(new PariMatchLiveScanner(), 0, scanningMillisecondsInterval);

