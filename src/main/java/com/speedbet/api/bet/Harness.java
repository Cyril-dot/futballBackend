package com.speedbet.api.bet;

import com.speedbet.api.match.*;
import org.springframework.beans.factory.ObjectProvider;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public class Harness {

    static class Sel extends BetSelection {
        UUID id = UUID.randomUUID(); UUID matchId; String result="PENDING", market, selection; BigDecimal odds;
        Sel(UUID m, String mk, String s, String o){matchId=m;market=mk;selection=s;odds=new BigDecimal(o);}
        public UUID getId(){return id;} public UUID getMatchId(){return matchId;}
        public String getResult(){return result;} public void setResult(String r){result=r;}
        public String getMarket(){return market;} public String getSelection(){return selection;}
        public BigDecimal getOddsLocked(){return odds;}
    }
    static class B extends Bet {
        UUID id=UUID.randomUUID(); BigDecimal stake, total; List<BetSelection> sels;
        B(String st,String to,BetSelection... s){stake=new BigDecimal(st);total=new BigDecimal(to);sels=Arrays.asList(s);}
        public UUID getId(){return id;} public BigDecimal getStake(){return stake;}
        public BigDecimal getTotalOdds(){return total;} public List<BetSelection> getSelections(){return sels;}
    }
    static class M extends Match {
        UUID id=UUID.randomUUID(); Integer h,a; Instant settled; Map<String,Object> meta=new HashMap<>();
        M(Integer h,Integer a,boolean s){this.h=h;this.a=a;if(s)settled=Instant.now();}
        public UUID getId(){return id;} public Integer getScoreHome(){return h;} public Integer getScoreAway(){return a;}
        public Instant getSettledAt(){return settled;} public Map<String,Object> getMetadata(){return meta;}
        public String getHomeTeam(){return "H";} public String getAwayTeam(){return "A";}
    }
    static class MS extends MatchService {
        Map<UUID,Match> byId=new HashMap<>();
        public Match getById(String s){ Match m=byId.get(UUID.fromString(s)); if(m==null) throw new RuntimeException("not found"); return m; }
    }
    static class BS extends BetService {
        BetStatus status; BigDecimal payout; int settleCalls, saveCalls;
        public void settleBet(Bet b, BetStatus s, BigDecimal p){status=s;payout=p;settleCalls++;}
        public void saveSelectionsOnly(Bet b){saveCalls++;}
    }

    static int pass=0, fail=0;
    static void check(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        System.out.printf("%-58s %-10s (expected %s)%n", (ok?"PASS  ":"FAIL  ")+name, actual, expected);
        if (ok) pass++; else fail++;
    }

    static Method settleOne; static Field msF, bsF, spF;
    static SettlementEngine engine(MS ms, BS bs) throws Exception {
        SettlementEngine e = new SettlementEngine();
        msF.set(e, ms); bsF.set(e, bs);
        spF.set(e, new ObjectProvider<SettlementEngine>(){
            public SettlementEngine getIfAvailable(){return e;} public SettlementEngine getObject(){return e;} });
        return e;
    }
    @SuppressWarnings("unchecked")
    static SettlementEngine.Outcome settle(SettlementEngine e, Bet b, Match trigger) throws Exception {
        return (SettlementEngine.Outcome) settleOne.invoke(e, b, trigger, new HashMap<UUID,Match>());
    }

    public static void main(String[] args) throws Exception {
        settleOne = SettlementEngine.class.getDeclaredMethod("settleOneBet", Bet.class, Match.class, Map.class);
        settleOne.setAccessible(true);
        msF = SettlementEngine.class.getDeclaredField("matchService"); msF.setAccessible(true);
        bsF = SettlementEngine.class.getDeclaredField("betService");   bsF.setAccessible(true);
        spF = SettlementEngine.class.getDeclaredField("selfProvider"); spF.setAccessible(true);

        // --- A: leg on a DIFFERENT, ALREADY-SETTLED match, still PENDING -----
        // This is the regression: old code treated it as "all settled" and priced
        // it at full odds without ever evaluating it.
        {
            M trigger = new M(2,0,false);          // home win
            M other   = new M(0,3,true);           // away win, already settled
            MS ms = new MS(); ms.byId.put(other.id, other);
            BS bs = new BS();
            B bet = new B("100","4.00",
                    new Sel(trigger.id,"1X2","HOME","2.00"),
                    new Sel(other.id,  "1X2","HOME","2.00"));   // this leg LOSES
            check("A settled-elsewhere leg is evaluated", settle(engine(ms,bs), bet, trigger), "LOST");
            check("A  payout", bs.payout, null);
        }
        // --- B: same, but the far leg WINS -> full payout, correctly priced ---
        {
            M trigger = new M(2,0,false);
            M other   = new M(0,3,true);
            MS ms = new MS(); ms.byId.put(other.id, other);
            BS bs = new BS();
            B bet = new B("100","4.00",
                    new Sel(trigger.id,"1X2","HOME","2.00"),
                    new Sel(other.id,  "1X2","AWAY","2.00"));
            check("B both legs win", settle(engine(ms,bs), bet, trigger), "WON");
            check("B  payout", bs.payout, "400.00");
        }
        // --- C: far leg on a match with no score yet -> DEFER, never price -----
        {
            M trigger = new M(2,0,false);
            M other   = new M(null,null,false);
            MS ms = new MS(); ms.byId.put(other.id, other);
            BS bs = new BS();
            B bet = new B("100","4.00",
                    new Sel(trigger.id,"1X2","HOME","2.00"),
                    new Sel(other.id,  "1X2","HOME","2.00"));
            check("C unsettleable leg defers", settle(engine(ms,bs), bet, trigger), "DEFERRED");
            check("C  settleBet not called", bs.settleCalls, 0);
            check("C  selections saved", bs.saveCalls, 1);
        }
        // --- D: unknown market on both legs -> all VOID -> stake refund --------
        {
            M trigger = new M(1,1,false);
            BS bs = new BS();
            B bet = new B("50","3.00",
                    new Sel(trigger.id,"MYSTERY_MARKET","HOME","3.00"));
            check("D all-void slip", settle(engine(new MS(),bs), bet, trigger), "VOID");
            check("D  stake refunded exactly", bs.payout, "50.00");
        }
        // --- E: quarter handicap half-loss -> pays HALF the stake ---------------
        // Old code clamped effectiveOdds up to 1.0 and refunded the FULL stake.
        {
            M trigger = new M(0,0,false);
            BS bs = new BS();
            B bet = new B("100","2.00",
                    new Sel(trigger.id,"ASIAN_HANDICAP","HOME -0.25","2.00"));
            check("E half-lost quarter line", settle(engine(new MS(),bs), bet, trigger), "WON");
            check("E  payout is half stake", bs.payout, "50.00");
        }
        // --- F: quarter handicap half-win --------------------------------------
        {
            M trigger = new M(0,0,false);   // draw: +0.25 is half-won
            BS bs = new BS();
            B bet = new B("100","2.00",
                    new Sel(trigger.id,"ASIAN_HANDICAP","HOME +0.25","2.00"));
            check("F half-won quarter line", settle(engine(new MS(),bs), bet, trigger), "WON");
            check("F  payout = stake x 1.5", bs.payout, "150.00");
        }
        // --- G: void leg divided out of the accumulator -------------------------
        {
            M trigger = new M(2,1,false);
            BS bs = new BS();
            B bet = new B("10","6.00",
                    new Sel(trigger.id,"1X2","HOME","2.00"),
                    new Sel(trigger.id,"NONSENSE","???","3.00"));
            check("G void leg divided out", settle(engine(new MS(),bs), bet, trigger), "WON");
            check("G  payout = 10 x 2.00", bs.payout, "20.00");
        }
        // --- H: over/under parsing variants ------------------------------------
        {
            M trigger = new M(1,1,false);   // 2 goals
            for (String[] t : new String[][]{{"o2.5","LOST"},{"Over 1,5","WON"},{"under 2.5","WON"},{"U2","PUSH"},{"+2.5","LOST"}}) {
                BS bs = new BS();
                B bet = new B("10","2.00", new Sel(trigger.id,"OVER_UNDER",t[0],"2.00"));
                SettlementEngine.Outcome o = settle(engine(new MS(),bs), bet, trigger);
                String legResult = bet.getSelections().get(0).getResult();
                check("H O/U '"+t[0]+"'", legResult, t[1]);
            }
        }
        // --- I: half-time via alternate metadata key ----------------------------
        {
            M trigger = new M(2,2,false);
            trigger.meta.put("ht_home","0"); trigger.meta.put("ht_away","1");
            BS bs = new BS();
            B bet = new B("10","2.00", new Sel(trigger.id,"Half Time","AWAY","2.00"));
            settle(engine(new MS(),bs), bet, trigger);
            check("I half-time alt metadata key", bet.getSelections().get(0).getResult(), "WON");
        }

        System.out.printf("%n%d passed, %d failed%n", pass, fail);
    }
}