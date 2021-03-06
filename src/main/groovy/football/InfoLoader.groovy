package football

import football.entity.ClubInfo
import football.entity.CountryInfo
import football.entity.EventReport
import football.entity.LeagueInfo
import football.entity.MatchLineUp
import football.entity.MatchReport
import football.entity.PlayerInfo
import football.entity.StatisticsReport
@Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.7')
import groovy.json.JsonSlurper
import groovy.util.slurpersupport.GPathResult
import groovy.util.slurpersupport.NodeChild
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.HttpResponseDecorator

import static football.entity.EventReport.EventType.ASSIST
import static football.entity.EventReport.EventType.GOAL
import static football.entity.PlayerInfo.Position.getByDisplayName
import static groovyx.net.http.ContentType.TEXT
import static java.time.LocalDate.parse
import static java.time.format.DateTimeFormatter.ofPattern
import static java.util.Arrays.asList
import static java.util.Locale.ENGLISH

slurper = new XmlSlurper()
soccerWayUrl = 'http://int.soccerway.com/competitions/?ICID=TN_02'
http = new HTTPBuilder(soccerWayUrl)
html = http.get([:])
SOCCER_WAY_NATIONAL_PATH = '/national/'
siteUrl = 'http://int.soccerway.com'
requestForLeaguesPrefix = siteUrl +
        '/a/block_competitions_index_club_domestic' +
        '?block_id=page_competitions_1_block_competitions_index_club_domestic_4' +
        '&callback_params=%7B%22level%22%3A%222%22%7D' +
        '&action=expandItem' +
        '&params=%7B%22area_id%22%3A%22'
requestForLeaguesSuffix = '%22%2C%22level%22%3A2%2C%22item_key%22%3A%22area_id%22%7D'

startFromCountry = 'England'
COUNTRIES = new TreeMap<>()
LEAGUES = new HashMap<>()
CLUBS = new HashMap<>()
PLAYERS = new HashMap<>()
MATCH_REPORTS = new HashMap<>()
MAX_ERRORS_ATTEMPTS = 5
MAX_MAIN_LEAGUES_AMOUNT = 5

justDoIt()

void justDoIt() {
    String outputFileName = 'statistics'
    String outputFileExtension = '.log'
    PrintStream out = null;
    PrintStream err = null;
    if (outputFileName != null) {
        out = new PrintStream(new BufferedOutputStream(new FileOutputStream(new File(outputFileName + outputFileExtension))));
        System.setOut(out);
        err = new PrintStream(new BufferedOutputStream(new FileOutputStream(new File(outputFileName + '_err' + outputFileExtension))));
        System.setErr(err);
    }

    System.out.println('-------------------STARTED--------------------')
    List<NodeChild> countries = html."**".findAll {
        def node = it as NodeChild
        def name = node.name().toLowerCase()
        def attributes = node.attributes()
        def countryLinkPath = attributes.get('href') as String
        String countryName = it.toString().trim() as String
        boolean isMatch = name == 'a' && attributes.containsKey('href') && countryLinkPath.count('/') == 4 && countryLinkPath.contains('/a')
        if(isMatch) {
            int compared = countryName.compareTo(startFromCountry)
            return isMatch && -1 < compared
        }
    }
    System.out.println("Found ${countries.size()} countriesRuToEn")

    List<CountryInfo> countriesPathsEntries = countries.collect {
        String path = it.attributes().get('href') as String
        String withoutNationalPath = path.replace(SOCCER_WAY_NATIONAL_PATH as String, '')
        String codeWithSlashes = withoutNationalPath.substring(withoutNationalPath.indexOf('/'))
        String code = codeWithSlashes.replace('/', '')
        String number = code.replace('a', '')
        String urlRequest = requestForLeaguesPrefix + number + requestForLeaguesSuffix
        CountryInfo countryInfo = new CountryInfo(name: it.toString().trim(), leaguesUrlRequest: urlRequest)
        COUNTRIES[countryInfo.name] = countryInfo
        countryInfo
    }

    countriesPathsEntries.each {
        System.out.println("FILLING COUNTRY INFO FOR ${it.name.toUpperCase()} BY URL ${it.leaguesUrlRequest}")
        def htmlContent = getHtmlContent(it.getLeaguesUrlRequest())
        htmlContent = htmlContent.replace('&', 'and')
        List<NodeChild> leaguesNodes = findLeaguesNodes(slurper.parseText(htmlContent))
        System.out.println("FOR COUNTRY ${it.name.toUpperCase()} FOUND ${leaguesNodes.size()}")
        it.leagueInfos = getLeaguesInfos(leaguesNodes)
        System.out.println("INFO FOR COUNTRY ${it.name.toUpperCase()} IS FILLED\n\n\n")
    }

    println countriesPathsEntries

    if (outputFileName != null) {
        out.close();
        err.close();
    }
}


List<LeagueInfo> getLeaguesInfos(List<NodeChild> leagues) {
    leagues.collect {
        LeagueInfo leagueInfo
        String leagueName = it.toString().trim()
        System.out.println("League ${leagueName}")
        String leagueRequestUrl = siteUrl + it.attributes().get('href')
        String leagueUrl = getLeagueUrl(leagueRequestUrl)
        def tablesUrl = leagueUrl + 'tables'
        System.out.println("Leagues ${leagueName} tables url ${tablesUrl}")
        def clubsHtml = new HTTPBuilder(tablesUrl).get([:])
        leagueInfo = new LeagueInfo(name: leagueName, url: leagueRequestUrl, leagueUrl: leagueUrl, tablesUrl: tablesUrl)
        System.out.println("Getting clubs info for league ${leagueName}")
        leagueInfo.clubs = getClubsInfo(clubsHtml as GPathResult)
        leagueInfo = new LeagueInfo(name: leagueName, url: leagueRequestUrl, leagueUrl: leagueUrl)
        LEAGUES[leagueInfo.url] = leagueInfo
        System.out.println("Info for league ${leagueInfo.name} is filled\n\n")
        leagueInfo
    }
}

Set<ClubInfo> getClubsInfo(GPathResult clubsHtml) {
    System.out.println("Getting clubs info for league ")
    Set<ClubInfo> clubsInfos = new HashSet<>()
    addClubsInfos(clubsHtml, clubsInfos)

    clubsInfos.each {
        errorAttempts = 0
        ClubInfo clubInfo = it
        try {
            error:
            fillSquadInfo(clubInfo)
            fillMatchesInfo(clubInfo)
            System.out.println("Info for club ${clubInfo.name} is filled\n")
        } catch (e) {
            System.err.println ('getClubsInfo.each - ' + clubInfo.url + ' - ' + e.getMessage())
            if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
        }
    }

    clubsInfos
}

void fillMatchesInfo(ClubInfo clubInfo) {
    errorAttempts = 0
    try {
        System.out.println("Filling matches info for club ${clubInfo.name}")
        error:
        def clubHtml = new HTTPBuilder(clubInfo.url + 'matches').get([:])
        clubInfo.matches = new HashMap<>()
        putCompetitions(clubHtml, clubInfo)

        GPathResult matchesTbody = clubHtml?."**"?.find {
            it.name().toLowerCase() == 'tbody'
        } as GPathResult

        for (NodeChild tr in matchesTbody.children()) {
            addMatchReport(tr, clubInfo)
        }

    } catch (e) {
        System.err.println('fillMatchesInfo.clubInfo - ' + clubInfo.name + ' ' + clubInfo.url + ' - ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
    }

}

void addMatchReport(NodeChild tr, ClubInfo clubInfo) {
    System.out.println("Adding match info for club ${clubInfo.name}")
    def (competitionName, MatchReport matchReport, boolean isNewlyCreated) = getMatchReport(tr)
    if (clubInfo?.matches?.get(competitionName)?.contains(matchReport)) {
        System.err.println('addMatchReport - ' + matchReport + ' - was not added')
        return
    } else if(!isNewlyCreated) return

    errorAttempts = 0
    try {
        error:
        clubInfo.getCompetitionMatches(competitionName) << matchReport
        ClubInfo opponent = matchReport.home == clubInfo ? matchReport.away : matchReport.home
        opponent.getCompetitionMatches(competitionName) << matchReport

        def matchSummaryHtml = new HTTPBuilder(matchReport.url).get([:])

        GPathResult infoHml = findTagWithAttributeValue(matchSummaryHtml, 'div', 'class', 'block_match_info')
        GPathResult goalsHml = findTagWithAttributeValue(matchSummaryHtml, 'table', 'class', 'matches events')
        GPathResult lineupsHtml = findTagWithAttributeValue(matchSummaryHtml, 'div', 'class', 'block_match_lineups')
        GPathResult substitutionsHtml = findTagWithAttributeValue(matchSummaryHtml, 'div', 'class', 'block_match_substitutes')

        if (lineupsHtml != null) {
            System.out.println("Filling full teams statistics")
            fillPlayersStatistics(matchReport, lineupsHtml, substitutionsHtml)
        } else if (goalsHml != null) {
            System.out.println("Filling only goals teams statistics")
            fillMatchGoalsStatistics(matchReport, goalsHml)
        } else if (infoHml != null) {
            System.out.println("Filling only summary teams statistics")
            fillMatchSummaryInfo(matchReport, infoHml)
        }
    } catch (e) {
        System.err.println('addMatchReport - ' + matchReport + ' - ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
    }

}

void fillPlayersStatistics(MatchReport matchReport, GPathResult lineUp, GPathResult subst) {
    fillHomeMatchLineUps(matchReport, lineUp, subst)
    fillAwayLineUps(matchReport, lineUp, subst)
}

void fillHomeMatchLineUps(MatchReport matchReport, GPathResult lineUp, GPathResult subst) {
    System.out.println("Filling home match line up for club ${matchReport.home.name}")
    GPathResult homeLineUp = findTagWithAttributeValue(findTagWithAttributeValue(lineUp, 'div', 'class', 'container left'), 'tbody', null, null)
    System.out.println("Filling start 11 for home club")
    List<PlayerInfo> homeStart11 = fillEventReportAndCreateLineUpForClub(homeLineUp, matchReport.resultReport, matchReport.home)
    GPathResult homeSubst = findTagWithAttributeValue(findTagWithAttributeValue(subst, 'div', 'class', 'container left'), 'tbody', null, null)
    System.out.println("Filling substitutions for home club")
    List<PlayerInfo> homeSubstitution = fillEventReportAndCreateLineUpForClub(homeSubst, matchReport.resultReport, matchReport.home)
    matchReport.lineups[matchReport.home] = new MatchLineUp(start11: homeStart11, substitutions: homeSubstitution)
}

void fillAwayLineUps(MatchReport matchReport, GPathResult lineUp, GPathResult subst) {
    System.out.println("Filling home match line up for club ${matchReport.away.name}")
    GPathResult awayLineUp = findTagWithAttributeValue(findTagWithAttributeValue(lineUp, 'div', 'class', 'container right'), 'tbody', null, null)
    System.out.println("Filling start 11 for away club")
    List<PlayerInfo> awayStart11 = fillEventReportAndCreateLineUpForClub(awayLineUp, matchReport.resultReport, matchReport.away)
    GPathResult awaySubst = findTagWithAttributeValue(findTagWithAttributeValue(subst, 'div', 'class', 'container right'), 'tbody', null, null)
    System.out.println("Filling substitutions for away club")
    List<PlayerInfo> awaySubstitution = fillEventReportAndCreateLineUpForClub(awaySubst, matchReport.resultReport, matchReport.away)
    matchReport.lineups[matchReport.away] = new MatchLineUp(start11: awayStart11, substitutions: awaySubstitution)
}

GPathResult findTagWithAttributeValue(matchSummaryHtml, String tagName, String attrName, String className) {
    matchSummaryHtml."**".find {
        isTagWithAttributeValue(it as NodeChild, tagName, attrName, className)
    } as GPathResult
}

boolean isTagWithAttributeValue(NodeChild node, String tagName, String attrName, String className) {
    try {
        if (!isBlank(tagName)) false
        def isTag = node.name().toLowerCase() == tagName
        if (!isBlank(attrName) && !isBlank(className)) {
            if (isTag && (node.attributes().get(attrName) as String)?.contains(className)) {
                return true
            }
            return false
        }
        return isTag
    } catch (e) {
        System.err.println('isTagWithAttributeValue - ' + e.getMessage())
        return false
    }
}

List<PlayerInfo> fillEventReportAndCreateLineUpForClub(GPathResult lineup, StatisticsReport statisticsReport, ClubInfo clubInfo) {
    List<PlayerInfo> players = new ArrayList<PlayerInfo>();

    lineup."**".findAll {
        errorAttempts = 0
        try {
            error:
            NodeChild node = it as NodeChild
            if (isTagWithName(node, 'tr') && isTagWithAttributeValue(node.children().iterator().next() as NodeChild, 'td', 'class', 'player large-link')) {
                Iterator iterator = node.children().iterator()
                NodeChild firstNode = iterator.next() as NodeChild
                NodeChild playerLargeLinkTd
                if (firstNode.attributes().get('class') == 'shirtnumber') {
                    playerLargeLinkTd = iterator.next() as NodeChild
                } else {
                    playerLargeLinkTd = firstNode
                }
                NodeChild possibleA = playerLargeLinkTd.children().iterator().next() as NodeChild
                String playerPath
                if (possibleA.name().toLowerCase().trim() == 'a') {
                    playerPath = possibleA?.attributes()?.get('href') as String
                } else {
                    NodeChild a = possibleA.children().iterator().next() as NodeChild
                    playerPath = a.attributes().get('href')
                }
                PlayerInfo playerInfo = getPlayerInfo(playerPath)
                NodeChild bookingsTd = iterator.next() as NodeChild
                bookingsTd.children().each {
                    NodeChild eventSpan = it as NodeChild
                    NodeChild eventImg = eventSpan.children().iterator().next() as NodeChild
                    String minuteStr = eventSpan.toString()
                    int atMinute = minuteStr.find(/\d+/)?.toInteger() ?: 0
                    if (atMinute > 120) atMinute /= 10
                    EventReport.EventType eventType = defineEventType(eventImg.attributes().get('src') as String)
                    EventReport eventReport = new EventReport(atMinute as byte, eventType, playerInfo)
                    statisticsReport.addEventReport(clubInfo, eventReport)
                }
                players.add(playerInfo)
                return true
            }
        } catch (e) {
            System.err.println('fillEventReportAndCreateLineUpForClub.findAll - ' + e.getMessage())
            if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
        }
        false
    }
    return players
}

boolean isTagWithName(NodeChild node, String tagName) {
    !isBlank(tagName) && node.name().toLowerCase() == tagName
}

boolean isBlank(String str) {
    str == null || str.isEmpty()
}

List getMatchReport(NodeChild tr) {
    System.out.println("Getting match report")

    Iterator iterator = tr.children().iterator()
    NodeChild dayOfWeekNodeChild = iterator.next() as NodeChild
    String dayOfWeek = dayOfWeekNodeChild.toString().trim() /*dd/MM/yyyy*/

    NodeChild fullDateNodeChild = iterator.next() as NodeChild
    String fullDate = fullDateNodeChild.toString().trim()

    NodeChild competitionTd = iterator.next() as NodeChild
    NodeChild competitionA = competitionTd.children().iterator().next() as NodeChild
    String competitionCode = competitionA.toString().trim()
    String competitionName = competitionA.attributes().get('title')

    NodeChild homeClubTd = iterator.next() as NodeChild
    NodeChild homeClubA = homeClubTd.children().iterator().next() as NodeChild
    String homeClubUrl = homeClubA.attributes().get('href')

    NodeChild scoreTd = iterator.next() as NodeChild
    NodeChild scoreA = scoreTd.children().iterator().next() as NodeChild

    def pathUrl = scoreA.attributes().get('href')
    String matchDetailsUrl = siteUrl + pathUrl

    if (MATCH_REPORTS.containsKey(pathUrl)) {
        System.out.println("Match report already exists ${matchDetailsUrl}")
        return [competitionName, MATCH_REPORTS.get(pathUrl), false]
    }

    NodeChild awayClubTd = iterator.next() as NodeChild
    NodeChild awayClubA = awayClubTd.children().iterator().next() as NodeChild
    String awayClubUrl = awayClubA.attributes().get('href')
    def homeClubName = homeClubA.toString().trim()
    def awayClubName = awayClubA.toString().trim()
    System.out.println("Created new match report: ${dayOfWeek} | ${fullDate} | ${competitionCode} | ${competitionName} | ${homeClubName} vs ${awayClubName} | ${matchDetailsUrl}")
    MatchReport matchReport = new MatchReport(
            url: matchDetailsUrl,
            date:  parse(fullDate, ofPattern('dd/MM/yy', ENGLISH)),
            home: getClubByUrl(homeClubName, homeClubUrl),
            away: getClubByUrl(awayClubName, awayClubUrl)
    )
    MATCH_REPORTS[matchReport.url] = matchReport
    [competitionName, matchReport, true]
}

ClubInfo getClubByUrl(String name, String clubUrl) {
    if ((CLUBS as Map).containsKey(clubUrl)) return CLUBS.get(clubUrl)
    System.out.println("Does not contain club ${name} by url ${siteUrl + clubUrl}, creating new one")
    ClubInfo clubInfo = new ClubInfo(name, siteUrl + clubUrl)
    CLUBS[clubUrl] = clubInfo
    return clubInfo
}

void putCompetitions(clubHtml, ClubInfo clubInfo) {
    GPathResult competitionsContainer = clubHtml?."**"?.find {
        it.name().toLowerCase() == 'ul' && it.attributes().get('id')?.contains('page_team_')
    } as GPathResult

    competitionsContainer?."**"?.findAll {
        NodeChild node = it as NodeChild
        def isCompetitionNode = node.name().toLowerCase() == 'a'
        def competitionName = node.toString().trim()
        if (isCompetitionNode && competitionName != 'All' && !clubInfo.matches.containsKey(competitionName)) {
            clubInfo.getCompetitionMatches(competitionName)
        }
        isCompetitionNode
    }
}

void fillSquadInfo(ClubInfo clubInfo) {
    System.out.println("Filling squad for club ${clubInfo.name}")
    errorAttempts = 0
    try {
        error:
        def clubHtml = new HTTPBuilder(clubInfo.url + 'squad').get([:])
        clubInfo.allPlayers = new HashSet<>()
        GPathResult tBody = clubHtml?."**"?.find {
            it.name().toLowerCase() == 'tbody'
        } as GPathResult

        tBody?."**"?.findAll {
            NodeChild node = it as NodeChild
            String playerPath = node.attributes().get('href')
            def isPlayerNode = node.name().toLowerCase() == 'a' && playerPath?.contains('/players/')
            if (isPlayerNode) {
                clubInfo.allPlayers.add(getPlayerInfo(playerPath))
            }
            isPlayerNode
        }
    } catch (e) {
        System.err.println('fillSquadInfo.clubInfo - ' + clubInfo.name + ' ' + clubInfo.url + ' - ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
    }
}

PlayerInfo createPlayerInfo(String playerPath) {
    def url = siteUrl + playerPath
    def playerHtml = new HTTPBuilder(url).get([:])

    PlayerInfo playerInfo = new PlayerInfo(url: url)
    PLAYERS[playerInfo.url] = playerInfo

    String dtName
    playerHtml."**".findAll {
        NodeChild node = it as NodeChild
        if (node.name().toLowerCase() == 'dt') {
            dtName = node.toString()
        }
        if (node.name().toLowerCase() == 'dd') {
            fillPassportData(playerInfo, node, dtName)
            return true
        }
        return false
    }
    playerInfo
}

void fillPassportData(PlayerInfo playerInfo, NodeChild node, String dtName) {
    errorAttempts = 0
    try {
        error:
        def value = node.toString().trim()
        if (value == null || value.isEmpty()) return
        switch (dtName) {
            case 'First name': playerInfo.name = value
                break
            case 'Last name': playerInfo.surname = value
                break
            case 'Date of birth':
                playerInfo.birthday = parse(value, ofPattern('d MMMM yyyy', ENGLISH))
                break
            case 'Nationality': playerInfo.nationality = value
                break
            case 'Position': playerInfo.positions = asList(getByDisplayName(value))
                break
            case 'Height': playerInfo.height = value.find(/\d+/).toInteger()
                break
            case 'Weight': playerInfo.weight = value.find(/\d+/).toInteger()
                break
        }
    } catch (e) {
        System.err.println('fillPassportData - ' + playerInfo + ' ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
    }
}

void addClubsInfos(GPathResult clubsHtml, clubsInfos) {
    GPathResult tBody = clubsHtml."**".find {
        it.name().toLowerCase() == 'tbody'
    } as GPathResult

    tBody."**".findAll {
        NodeChild node = it as NodeChild
        String teamUrl = node.attributes().get('href')
        def isTeam = node.name().toLowerCase() == 'a' && teamUrl?.contains('/teams/')
        if (isTeam) {
            def clubName = node.toString().trim()
            System.out.println("Getting clubs info for club ${clubName} and url ${teamUrl}")
            clubsInfos.add(getClubByUrl(clubName, teamUrl))
        }
        isTeam
    }
}

String getLeagueUrl(String leagueUrl) {
    errorAttempts = 0
    try {
        error:
        def html = new HTTPBuilder(leagueUrl).get([:]) { resp, reader -> resp.headers }
        HttpResponseDecorator.HeadersDecorator headerDecorator = html as HttpResponseDecorator.HeadersDecorator
        HttpResponseDecorator wrapper = headerDecorator.this$0
        def locationWithBrackets = wrapper.getContext().getAttribute('http.cookie-origin') as String
        leagueUrl = 'http://' + locationWithBrackets.replace('[', '').replace(']', '').replace(':80', '')
        String wrongUrl = leagueUrl
        if (leagueUrl.contains('20162017')) {
            def needSeason = '20152016'
            def replaced = leagueUrl.replace('20162017', needSeason)
            leagueUrl = replaced.substring(0, replaced.indexOf(needSeason) + needSeason.length())
            if(wrongUrl.contains('final-stages')) {
                leagueUrl += + '/regular-season/'
            }
            html = new HTTPBuilder(leagueUrl).get([:]) { resp, reader -> resp.headers }
            headerDecorator = html as HttpResponseDecorator.HeadersDecorator
            wrapper = headerDecorator.this$0
            locationWithBrackets = wrapper.getContext().getAttribute('http.cookie-origin') as String
            leagueUrl = 'http://' + locationWithBrackets.replace('[', '').replace(']', '').replace(':80', '')
        }
        leagueUrl
    } catch (Exception e) {
        System.err.println('getLeagueUrl - ' + leagueUrl + ' - ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
        null
    }
}


List<NodeChild> findLeaguesNodes(GPathResult rootNode) {
    int mainLeaguesAmount = 0
    rootNode.children().findResults {
        NodeChild li = it as NodeChild
        NodeChild div = li.children().iterator().next() as NodeChild
        def iterator = div.children().iterator()
        NodeChild toCheck = iterator.next() as NodeChild
        String name = toCheck.name()
        GPathResult span = iterator.next() as GPathResult
        if(name == 'a' && toCheck.attributes().containsKey('href') && span.toString().trim().equals('Domestic league')) {
            String leagueName = toCheck.toString().trim()
            if(leagueName.contains('U21') || leagueName.contains('Women')) {
                return toCheck
            } else if(++mainLeaguesAmount < MAX_MAIN_LEAGUES_AMOUNT) {
                return toCheck
            }
        }
        null
    } as List<NodeChild>
}

String getHtmlContent(String leaguesUrlRequest) {
    def client = new HTTPBuilder(leaguesUrlRequest)
    client.setHeaders(Accept: 'application/json')
    def text = client.get(contentType: TEXT)
    Map json = new JsonSlurper().parse(text) as Map
    List elements = json.get('commands') as List
    Map firstElement = elements.get(0) as Map
    Map parameters = firstElement.get('parameters') as Map
    parameters.get('content') as String
}

void fillMatchGoalsStatistics(MatchReport matchReport, GPathResult html) {

    List<NodeChild> goalsTrs = html."**".findAll {
        isTagWithAttributeValue(it as NodeChild, 'tr', 'class', 'event    expanded')
    }
    goalsTrs.each {
        NodeChild goalTrNode = it as NodeChild
        def iterator = goalTrNode.children().iterator()
        NodeChild homePlayerTd = iterator.next() as NodeChild
        List<EventReport> reports

        if (!homePlayerTd.children().iterator().next().toString().trim().isEmpty()) {
            //home direction
            reports = getHomeScoredReports(homePlayerTd)
            matchReport.resultReport.addEventReport(matchReport.home, reports[0])
            matchReport.resultReport.addEventReport(matchReport.home, reports[1])
        } else {
            reports = getAwayScoredReports(iterator)
            matchReport.resultReport.addEventReport(matchReport.away, reports[0])
            matchReport.resultReport.addEventReport(matchReport.away, reports[1])
        }

    }
}

List<EventReport> getAwayScoredReports(Iterator iterator) {
    EventReport scoredReport, assistedReport
    iterator.next() //score snapshot
    //away direction
    NodeChild awayPlayerTd = iterator.next() as NodeChild
    def subIterator = (awayPlayerTd.children().iterator().next() as NodeChild).children().iterator()
    NodeChild scoredAtMinuteSpan = subIterator.next() as NodeChild
    def minute = scoredAtMinuteSpan.toString().find(/\d+/)?.toInteger() ?: 0
    if (minute > 120) minute /= 10
    NodeChild awayScored = subIterator.next() as NodeChild
    PlayerInfo scoredByPlayerInfo = getPlayerInfo(awayScored.attributes().get('href') as String)
    scoredReport = new EventReport(minute as byte, GOAL, scoredByPlayerInfo)
    assistedReport = getAssistReport(subIterator, minute)
    [scoredReport, assistedReport]
}

PlayerInfo getPlayerInfo(String playerPath) {
    if (!PLAYERS.containsKey(siteUrl + playerPath)) {
        PlayerInfo playerInfo = createPlayerInfo(playerPath)
        PLAYERS[siteUrl + playerPath] = playerInfo
        return playerInfo
    } else {
        return PLAYERS.get(siteUrl + playerPath)
    }
}

List<EventReport> getHomeScoredReports(NodeChild homePlayerTd) {
    EventReport scoredReport, assistedReport
    def subIterator = (homePlayerTd.children().iterator().next() as NodeChild).children().iterator()
    NodeChild homeScored = subIterator.next() as NodeChild
    PlayerInfo scoredByPlayerInfo = getPlayerInfo(homeScored.attributes().get('href') as String)
    NodeChild scoredAtMinuteSpan = subIterator.next() as NodeChild
    def minute = scoredAtMinuteSpan.toString().find(/\d+/)?.toInteger() ?: 0
    if (minute > 120) minute /= 10
    scoredReport = new EventReport(minute as byte, GOAL, scoredByPlayerInfo)
    assistedReport = getAssistReport(subIterator, minute)
    [scoredReport, assistedReport]
}

EventReport getAssistReport(Iterator subIterator, Number minute) {
    if (subIterator.hasNext()) {
        subIterator.next() as NodeChild //br
        NodeChild assistSpan = subIterator.next() as NodeChild
        NodeChild assistPlayerA = assistSpan.children().iterator().next() as NodeChild
        PlayerInfo assistPlayerInfo = getPlayerInfo(assistPlayerA.attributes().get('href') as String)
        return new EventReport(minute as byte, ASSIST, assistPlayerInfo)
    }
    null
}

void fillMatchSummaryInfo(MatchReport matchReport, GPathResult html) {
    errorAttempts = 0
    try {
        error:
        String info = html.toString()
        String fullTimeAndSoOn
        if(info.contains('Half-time')) {
            String halfTimeAndSoOn = info.substring(info.indexOf('Half-time'))
            fullTimeAndSoOn = halfTimeAndSoOn.substring(halfTimeAndSoOn.indexOf('Full-time')).trim()
            String halTime = halfTimeAndSoOn - fullTimeAndSoOn
            List<Integer> halfTimeGoalsAmounts = addGoalsEventsReports(halTime.replace('Half-time',''), matchReport, -1 as byte)
            if(fullTimeAndSoOn.length() > 'Full-time10 - 10'.length()) {
                String[] parts = fullTimeAndSoOn.split(' - ')
                int secondTimeHomeGoalsAmount = parts[0].replace('Full-time','').toInteger() - halfTimeGoalsAmounts[0]
                secondTimeHomeGoalsAmount.times {
                    matchReport.resultReport.addEventReport(matchReport.home, new EventReport(-2 as byte, GOAL, null))
                }
                int secondTimeAwayGoalsAmount = parts[1].substring(0,parts[1].indexOf(parts[1].find(/\D/))).toInteger()
                secondTimeAwayGoalsAmount.times {
                    matchReport.resultReport.addEventReport(matchReport.home, new EventReport(-2 as byte, GOAL, null))
                }
            } else {
                String[] score = fullTimeAndSoOn.replace('Full-time','').split(' - ')
                int secondTimeHomeGoalsAmount = score[0].toInteger() - halfTimeGoalsAmounts[0]
                int secondTimeAwayGoalsAmount = score[1].toInteger() - halfTimeGoalsAmounts[1]
                secondTimeHomeGoalsAmount.times {
                    matchReport.resultReport.addEventReport(matchReport.home, new EventReport(-2 as byte, GOAL, null))
                }
                secondTimeAwayGoalsAmount.times {
                    matchReport.resultReport.addEventReport(matchReport.away, new EventReport(-2 as byte, GOAL, null))
                }
            }
        } else if(info.contains('Full-time')) {
            fullTimeAndSoOn = info.substring(info.indexOf('Full-time')).trim()
            if(fullTimeAndSoOn.length() < 'Full-time10 - 10'.length()) addGoalsEventsReports(fullTimeAndSoOn.replace('Full-time',''), matchReport, 0 as byte)
        }
    } catch (e) {
        System.err.println('fillPassportData - ' + playerInfo + ' ' + e.getMessage())
        if(++errorAttempts < MAX_ERRORS_ATTEMPTS) continue error
    }

}

List<Integer> addGoalsEventsReports(String halTime, matchReport, byte timeCode) {
    String[] score = halTime.split(' - ')
    int homeGoalsAmount = score[0].toInteger()
    int awayGoalsAmount = score[1].toInteger()
    homeGoalsAmount.times {
        matchReport.resultReport.addEventReport(matchReport.home, new EventReport(timeCode, GOAL, null))
    }
    awayGoalsAmount.times {
        matchReport.resultReport.addEventReport(matchReport.away, new EventReport(timeCode, GOAL, null))
    }
    [homeGoalsAmount, awayGoalsAmount]
}

EventReport.EventType defineEventType(String srcPath) {
    if (srcPath.endsWith("G.png")) {
        return eventType = GOAL
    } else if (srcPath.endsWith("PG.png")) {
        return eventType = EventReport.EventType.PENALTY_GOAL
    } else if (srcPath.endsWith("PM.png")) {
        return eventType = EventReport.EventType.PENALTY_MISSED
    } else if (srcPath.endsWith("OG.png")) {
        return eventType = EventReport.EventType.AUTO_GOAL
    } else if (srcPath.endsWith("YC.png")) {
        return eventType = EventReport.EventType.YELLOW_CARD
    } else if (srcPath.endsWith("Y2C.png")) {
        return eventType = EventReport.EventType.YELLOW_SECOND_CARD
    } else if (srcPath.endsWith("RC.png")) {
        return eventType = EventReport.EventType.RED_CARD
    }
    null
}