package ggp.tiltyard.scheduling;

import ggp.tiltyard.players.Player;
import ggp.tiltyard.hosting.Hosting;
import ggp.tiltyard.hosting.MatchData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.ggp.galaxy.shared.loader.RemoteResourceLoader;
import org.ggp.galaxy.shared.server.request.RequestBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.prodeagle.java.counters.Counter;

public class Scheduling {
    // Eventually we should support other repository servers. Figure out how
    // to do this in a safe, secure fashion (since the repository server can
    // inject arbitrary javascript into the visualizations).	
	private static final String GAME_REPO_URL = "http://games.ggp.org/base/";
	
	// This is a whitelist of games that the backend server can handle. Ideally we'll be able
	// to get rid of this at some point. This eliminates chess-like games and other large games
	// (e.g. Amazons, Knight Fight) and some games which aren't fully debugged, and probably a
	// few legit games that should be in the rotation (since whitelisting is a bad approach).
	/*
	private static final String[] safeGames = {
		"2pffa", "2pffa_zerosum", "2pttc", "3pffa", "3pttc", "3pConnectFour", "4pttc", "4pffa", "beatMania",
		"biddingTicTacToe", "biddingTicTacToe_10coins",  "blocker", "bomberman2p",
		"breakthrough", "breakthroughHoles", "breakthroughSmall", "breakthroughSuicide", "breakthroughWalls", "knightThrough", "escortLatch",
		"cephalopodMicro", "checkers", "checkersSmall",
		"checkersTiny", "chickentictactoe", "chineseCheckers1", "chineseCheckers2",
		"chineseCheckers3", "chineseCheckers4", "chineseCheckers6", "chinook", "cittaceot", "connect5",
		"connectFour", "connectFourLarge", "connectFourLarger", "connectFourSimultaneous",
		"connectFourSuicide", "dotsAndBoxes", "dotsAndBoxesSuicide", "dualConnect4",
		"eightPuzzle", "englishDraughts", "ghostMaze2p",
		"god", "golden_rectangle", "knightsTour",
		"lightsOut", "max_knights", "maze", "nineBoardTicTacToe", "numberTicTacToe", "pacman2p", "pacman3p",
		"pawnToQueen", "pawnWhopping", "peg", "pegEuro", "pentago", "pentagoSuicide",
		"qyshinsu", "reversi", "sheepAndWolf", "snake2p", "snakeParallel",
		"ttcc4_2player", "tictactoe_3player", "ticTacToe", "ticTacToeLarge", "ticTacToeLargeSuicide",
		"ticTacToeParallel", "ticTacToeSerial", "ticTicToe",
	};	
	*/
	// Also, for now, only use games which have valid base/input propositions
	private static final String[] safeGames = {
		"3pConnectFour", "englishDraughts", "dotsAndBoxes", "knightThrough",
		"breakthroughWalls", "reversi", "cephalopodMicro", "breakthrough",
		"nineBoardTicTacToe", "pentagoSuicide", "checkersSmall", "checkersTiny",
		"dotsAndBoxesSuicide", "maze", "ticTacToe", "ttcc4_2player", "connectFourLarge",
		"pegEuro", "eightPuzzle", "knightsTour", "chinook", "connectFourLarger",
		"connectFour", "breakthroughSmall", "peg", "connectFourSimultaneous",
		"escortLatch", "qyshinsu", "connectFourSuicide", "pentago", "blocker",
		"checkers", "2pffa_zerosum", "2pffa", "3pffa", "4pffa"
	};	

    public static void runSchedulingRound() throws IOException {
    	SchedulerConfig theConfig = SchedulerConfig.loadConfig();
        List<Player> theAvailablePlayers = Player.loadEnabledPlayers();
        long morePlayersIn = Long.MAX_VALUE;
        
        Counter.increment("Tiltyard.Scheduling.Round.Started");

        {
            // Find and clear all of the completed or wedged matches. For matches
            // which are still ongoing, mark the players in those matches as busy.
        	// Also keep track of how long it will be before some are available.
        	Set<MatchData> activeMatches = MatchData.loadMatches();
            Set<String> busyPlayerNames = new HashSet<String>();            
            for (MatchData activeMatch : activeMatches) {
            	List<String> matchPlayers = activeMatch.getPlayerNames();
            	if (activeMatch.isCompleted() && activeMatch.getTimeSinceLastChange() > 1000L*60*2) {
            		// Once a match has been completed for 2+ minutes, force it to be published one
            		// last time, handle its consequences for player scheduling, and then delete it.
            		handleStrikesForPlayers(activeMatch.getMatchInfo(), matchPlayers, theAvailablePlayers);
            		activeMatch.publish();
            		activeMatch.delete();
            	} else if (activeMatch.isWedged()) {
            		// When a match becomes wedged for whatever reason, issue abort requests to all of
            		// the involved players, force it to be published one last time, and then delete it.
            		activeMatch.abort();
            		activeMatch.publish();
            		activeMatch.delete();
            	} else if (activeMatch.hasComputerPlayers()) {
            		// Otherwise, if the match has computer players, mark those players as busy and
            		// note when they'll be available for new matches.
            		busyPlayerNames.addAll(matchPlayers);
            		morePlayersIn = Math.min(activeMatch.getExpectedTimeToCompletion(), morePlayersIn);
            	}
            }

            // For all of the players listed as enabled, record whether or not they're actually
            // identifying themselves as available. For players that are marked as pingable, we
            // only assign them matches if they don't identify themselves as busy.
            for (int i = theAvailablePlayers.size()-1; i >= 0; i--) {
                Player p = theAvailablePlayers.get(i);
                if (p.isEnabled() && p.isPingable()) {
                    String theInfoError = "";
                    String theInfoResponse = "";                    
                    try {
                        String theProperURL = p.getURL();
                        if (!theProperURL.startsWith("http://")) {
                            theProperURL = "http://" + theProperURL;
                        }
                        theInfoResponse = RemoteResourceLoader.postRawWithTimeout(theProperURL, RequestBuilder.getInfoRequest(), 2500);
                        p.resetPingStrikes();
                    } catch (IOException e) {
                    	theInfoError = e.toString();
                    	p.addPingStrike();
                    }
                    p.setInfo(theInfoResponse, theInfoError);
                    p.save();
                }
            }

            // Remove all of the players that aren't actually available, either because
            // we know they're in a match, or because they say they're busy, or because
            // they're disabled.
            for (int i = theAvailablePlayers.size()-1; i >= 0; i--) {
                Player p = theAvailablePlayers.get(i);                
                if (!p.isEnabled() || (p.isPingable() && !("available".equals(p.getInfoStatus().toLowerCase()))) || busyPlayerNames.contains(p.getName())) {
                    theAvailablePlayers.remove(i);
                }
            }
        }

        // At this point we've gotten everything up to date, and the only thing
        // left to do is schedule new matches. If the backends are being drained,
        // we don't schedule any new matches.
        if (theConfig.isDrained()) return;        
        
        // Figure out how many players are available. If no computer players are
    	// available, don't bother attempting to automatically schedule a match.
        int readyPlayers = theAvailablePlayers.size();
        if (readyPlayers == 0) return;
        
        // For all of the pending matches in the scheduling queue, consider
        // running each one. These have a higher priority than the regularly
        // scheduled automatic matches. When they schedule, they will remove
        // the appropriate players from the list of available players.
        Set<PendingMatch> pendingMatches = PendingMatch.loadPendingMatches();
    	for (PendingMatch match : pendingMatches) {
    		match.considerStarting(theAvailablePlayers);
    	}        

        // When there is only a single available player, but there are busy players,
        // and we expect one of those busy players to become available within 15 minutes,
        // don't assign that player into a match vs all random opponents. Instead, wait
        // until a real opponent becomes available.
        if (theAvailablePlayers.size() == 1 && morePlayersIn < 15*60*1000) {
        	return;
        }
        
        Counter.increment("Tiltyard.Scheduling.Round.AvailablePlayers");
        
        // Load the aggregated game metadata from the base repository server.
        int nGamesLookupAttempt = 0;
        JSONObject metadataForGames;
        while (true) {        	
	        try {
	        	metadataForGames = RemoteResourceLoader.loadJSON(GAME_REPO_URL + "games/metadata");
	            break;
	        } catch (Exception e) {
	        	if (nGamesLookupAttempt > 9) {
	        		throw new RuntimeException(e);
	        	}
	        }
	        nGamesLookupAttempt++;
	        // Backoff between requests
        	try {
				Thread.sleep(1000);
			} catch (InterruptedException e1) {
				;
			}	        
        }        

        // Collect all of the games which have visualizations, which we'll call the
        // set of "proper" games that will be played on Tiltyard. Also only allow games
        // on a whitelist of "safe" games that the backend can handle.
        Set<String> theSafeGames = new HashSet<String>(Arrays.asList(safeGames));
        List<String> properGameKeys = new ArrayList<String>();
        Map<String, JSONObject> properGames = new HashMap<String, JSONObject>();        
        Iterator<?> itr = metadataForGames.keys();
        while (itr.hasNext()) {
        	String key = itr.next().toString();
        	if (!theSafeGames.contains(key))
        		continue;
        	try {
	        	JSONObject gameMetadata = metadataForGames.getJSONObject(key);
	        	if (gameMetadata.has("stylesheet")) {
	        		properGames.put(key, gameMetadata);
	        		properGameKeys.add(key);
	        	}
        	} catch (JSONException e) {
        		throw new RuntimeException(e);
        	}
        }
        
        // Shuffle the list of known proper games, draw a game, and get ready to play.
        Collections.shuffle(properGameKeys);
        String gameKey = properGameKeys.get(0);        
        int nPlayersForGame, gameVersion;
        try {
	        nPlayersForGame = properGames.get(gameKey).getInt("numRoles");
	        gameVersion = properGames.get(gameKey).getInt("version");
        } catch (JSONException e) {
        	throw new RuntimeException(e);
        }
        String theGameURL = GAME_REPO_URL + "games/" + gameKey + "/v" + gameVersion + "/";

        // Shuffle the set of available players and then assign them to roles
        // in the game until we run out of players or roles. If we run out of
        // players, assign random players to fill the remaining roles. Lastly
        // shuffle the mapping of players to roles so that the random players
        // aren't always playing the last roles in the game.
        List<Player> theChosenPlayers = new ArrayList<Player>();
        Collections.shuffle(theAvailablePlayers);
        for (Player p : theAvailablePlayers) {
        	nPlayersForGame--;
        	theChosenPlayers.add(p);
        	if (nPlayersForGame == 0)
        		break;
        }
        while (nPlayersForGame > 0) {
        	nPlayersForGame--;
        	// null stands in for a random player.
        	theChosenPlayers.add(null);
        }
        Collections.shuffle(theChosenPlayers);
        
        // Once the final mapping of players to roles has been chosen,
        // extract the names and URLs from the players into a form that
        // will appear in the match creation request.
        List<String> playerURLsForMatch = new ArrayList<String>();
        List<String> playerNamesForMatch = new ArrayList<String>();
        for (Player p : theChosenPlayers) {        	
        	if (p == null) {
        		playerURLsForMatch.add(null);
        		playerNamesForMatch.add("Random");
        	} else {
        		playerURLsForMatch.add(p.getURL());
        		playerNamesForMatch.add(p.getName());
        	}
        }
        
        // Choose randomized start clocks and play clocks for the match.
        // Start clocks vary between 90 seconds and 180 seconds.
        // Play clocks vary between 15 seconds and 45 seconds.
        Random theRandom = new Random();        
        int startClock = 90 + 10*theRandom.nextInt(10);
        int playClock = 15 + 5*theRandom.nextInt(7);
        int previewClock = -1;

        // Start the match using the hybrid match hosting system.
       	Hosting.startMatch(theGameURL, playerURLsForMatch, playerNamesForMatch, previewClock, startClock, playClock);        	
        
        Counter.increment("Tiltyard.Scheduling.Round.Success");        
    }

    private static void handleStrikesForPlayers(JSONObject theMatchInfo, List<String> players, List<Player> thePlayers) {    	
        if (!theMatchInfo.has("errors")) return;        
        int[] numLegalMoves = new int[players.size()];
        for (int i = 0; i < numLegalMoves.length; i++) {
        	numLegalMoves[i] = 0;
        }
        
        int numTotalMoves;
        try {
            JSONArray theErrors = theMatchInfo.getJSONArray("errors");
            numTotalMoves = theErrors.length();
            for (int i = 0; i < theErrors.length(); i++) {
                JSONArray moveErrors = theErrors.getJSONArray(i);
                for (int j = 0; j < moveErrors.length(); j++) {
                    if (moveErrors.getString(j).isEmpty()) {
                    	numLegalMoves[j]++;
                    }
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        for (int i = 0; i < thePlayers.size(); i++) {
            int nPlayerIndex = players.indexOf(thePlayers.get(i).getName());
            if (nPlayerIndex > -1) {
            	if (numLegalMoves[nPlayerIndex] >= numTotalMoves/2) {
                    thePlayers.get(i).resetStrikes();
                } else {
                    thePlayers.get(i).addStrike();
                }
                thePlayers.get(i).save();
            }
        }
    }
    
	public static void doPost(String theURI, String in, HttpServletResponse resp) throws IOException {
		try {
			if (theURI.equals("start_match")) {
				JSONObject theRequest = new JSONObject(in);

				// Extract player codes from the request.
				List<String> playerCodes = new ArrayList<String>();
		        JSONArray thePlayerCodes = theRequest.getJSONArray("playerCodes");
		        for (int i = 0; i < thePlayerCodes.length(); i++) {
		        	String playerCode = thePlayerCodes.getString(i);
		        	playerCodes.add(playerCode);
		        }

		        // Extract match parameters from the request.
		        String gameURL = theRequest.getString("gameURL");
		        int previewClock = theRequest.getInt("previewClock");
		        int startClock = theRequest.getInt("startClock");
		        int playClock = theRequest.getInt("playClock");
		        int deadline = theRequest.getInt("deadline");
		        
		        // All games on Tiltyard must come from the base game repository.
		        if (!gameURL.startsWith("http://games.ggp.org/base/games/")) {
		        	resp.setStatus(500);
		        	return;
		        }

		        // Create the match and enqueue it (and possibly start it).
			    PendingMatch pending = new PendingMatch(gameURL, playerCodes, previewClock, startClock, playClock, System.currentTimeMillis() + deadline);
			    String matchKey = pending.considerStarting(new ArrayList<Player>());
			    if (matchKey == null) {
			    	resp.getWriter().println("queued");
			    } else {
			    	resp.getWriter().println(matchKey);
			    }
			}

            resp.setHeader("Access-Control-Allow-Origin", "tiltyard.ggp.org");
            resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "*");
            resp.setHeader("Access-Control-Allow-Age", "86400");        
            resp.setContentType("text/plain");
            resp.setStatus(200);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
	}

	public static void doGet(String reqURI, HttpServletResponse resp) {
		if (reqURI.equals("pending")) {
    		try {			
				List<JSONObject> pendingMatchesJSON = new ArrayList<JSONObject>();
		        Set<PendingMatch> pendingMatches = PendingMatch.loadPendingMatches();
	        	for (PendingMatch match : pendingMatches) {
	        			pendingMatchesJSON.add(match.toJSON());
	        	}
	        	JSONObject response = new JSONObject();
	        	response.put("pending", pendingMatchesJSON);        	
	        	resp.getWriter().println(response);
	            resp.setHeader("Access-Control-Allow-Origin", "*");
	            resp.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
	            resp.setHeader("Access-Control-Allow-Headers", "*");
	            resp.setHeader("Access-Control-Allow-Age", "86400");        
	            resp.setContentType("text/plain");
	            resp.setStatus(200);
    		} catch (JSONException je) {
    			throw new RuntimeException(je);
    		} catch (IOException ie) {
    			throw new RuntimeException(ie);
    		}
		}
	}
}