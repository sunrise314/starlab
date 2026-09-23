package com.starlab.link;

import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 干扰分析器（v3 干扰模型）
 * <p>
 * 计算期望服务卫星与指定地面站链路在以下三类干扰下的可用性：
 * <ol>
 *   <li><b>同频干扰 (Co-channel Interference)</b>：与目标卫星同频段的其他可见卫星，
 *       它们的下行信号会进入本站接收机造成干扰。干扰卫星之间信号非相干，
 *       功率按线性求和：I_co = Σ 10^(P_i/10) (mW)</li>
 *   <li><b>邻信道干扰 (ACI, Adjacent Channel Interference)</b>：相邻信道泄漏，
 *       被邻信道隔离度（ACIR）抑制后剩余的功率：I_aci = Σ 10^((P_i - ACIR)/10)</li>
 *   <li><b>宽带噪声底 (Wideband Noise)</b>：接收机热噪声 N = -174 + 10·log10(B) + NF</li>
 * </ol>
 * <p>
 * 载干比：C/I = C − 10·log10(I_co + I_aci + N)  (dB)
 * <p>
 * 链路可用性：可见 且 C/I > 阈值 且 SNR > 阈值
 * <p>
 * 物理依据：非相干干扰功率线性求和、邻信道隔离 ACIR、链路预算 C 与 LinkCalculator 一致。
 */
@Component
public class InterferenceCalculator {

    /** 邻信道隔离度 (dB) — 相邻信道泄漏抑制，典型 30 dB */
    private static final double ACI_ISOLATION_DB = 30.0;
    /** 解调所需最小载干比 C/I (dB) — QPSK 1/2 约 6 dB，留余量取 9 dB */
    private static final double CIR_THRESHOLD_DB = 9.0;
    /** 解调所需最小 SNR (dB) */
    private static final double SNR_THRESHOLD_DB = 3.0;

    private final LinkCalculator linkCalculator;

    public InterferenceCalculator(LinkCalculator linkCalculator) {
        this.linkCalculator = linkCalculator;
    }

    /**
     * 分析指定目标卫星与地面站链路在同频/邻频/噪声干扰下的可用性
     *
     * @param targetSat 用户期望通信的目标卫星（服务星）
     * @param station   地面站
     * @param allSats   当前时刻所有卫星位置（含 targetSat，用于枚举干扰源）
     * @return 含干扰字段的 LinkResult（其余 14 字段沿用 linkCalculator 物理层结果）
     */
    public LinkResult analyze(SatellitePosition targetSat,
                              GroundStation station,
                              List<SatellitePosition> allSats) {
        // 1. 期望链路基础物理层（FSPL / 雨衰 / 大气 / SNR / Shannon）
        LinkResult targetLink = linkCalculator.calculate(targetSat, station);

        // 2. 期望信号接收功率 C (dBm)
        double carrierDbm = linkCalculator.receivedPowerDbm(targetLink);

        // 3. 枚举同频/邻信道干扰源（其他可见卫星）
        double coChannelPowerMw = 0.0;
        double aciPowerMw = 0.0;
        int coChannelCount = 0;

        for (SatellitePosition sat : allSats) {
            if (sat.satelliteId().equals(targetSat.satelliteId())) continue;
            LinkResult iLink = linkCalculator.calculate(sat, station);
            if (!iLink.visible()) continue;  // 不可见的卫星不构成干扰

            double iDbm = linkCalculator.receivedPowerDbm(iLink);
            // 同频：全部功率进入接收带宽
            coChannelPowerMw += dbmToMw(iDbm);
            coChannelCount++;
            // 邻信道：被 ACIR 抑制后剩余
            aciPowerMw += dbmToMw(iDbm - ACI_ISOLATION_DB);
        }

        // 4. 宽带噪声底 N (mW)
        double noiseDbm = linkCalculator.noiseFloorDbm();
        double noiseMw = dbmToMw(noiseDbm);

        // 5. 合计干扰功率 (dBm)：同频 + 邻信道 + 噪声
        double totalInterfMw = coChannelPowerMw + aciPowerMw + noiseMw;
        double interferencePowerDbm = mwToDbm(totalInterfMw);

        // 6. 载干比 C/I = C − I (dB)
        double cirDb = Math.round((carrierDbm - interferencePowerDbm) * 100.0) / 100.0;

        // 7. 链路可用性：目标卫星可见 且 C/I > 阈值 且 SNR > 阈值
        boolean linkAvailable = targetLink.visible()
                && cirDb > CIR_THRESHOLD_DB
                && targetLink.snrDb() > SNR_THRESHOLD_DB;

        return LinkResult.withInterference(targetLink,
                Math.round(interferencePowerDbm * 100.0) / 100.0,
                coChannelCount,
                cirDb,
                linkAvailable);
    }

    /**
     * 批量分析：对所有 (目标卫星 × 地面站) 组合计算干扰可用性
     *
     * @param allSats     当前时刻所有卫星位置
     * @param stations    所有地面站
     * @param visibleOnly 是否只返回目标卫星对该站可见的组合
     * @return 干扰分析结果列表（每条对应一个 sat-station 对）
     */
    public List<LinkResult> analyzeAll(List<SatellitePosition> allSats,
                                       List<GroundStation> stations,
                                       boolean visibleOnly) {
        return allSats.stream()
                .flatMap(sat -> stations.stream()
                        .map(st -> analyze(sat, st, allSats)))
                .filter(r -> !visibleOnly || r.visible())
                .toList();
    }

    private static double dbmToMw(double dbm) {
        return Math.pow(10, dbm / 10.0);
    }

    private static double mwToDbm(double mw) {
        return mw <= 0 ? -99.0 : 10 * Math.log10(mw);
    }
}
