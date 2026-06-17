package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class LBFGSMinimizer {
    private static final Logger logger = LoggerFactory.getLogger(LBFGSMinimizer.class);

    public interface VectorSpace<T> {
        T clone(T v, boolean zero);
        void addScaled(T out, T v1, double s, T v2);
        double dot(T a, T b);
        void scale(T v, double s);
    }

    private final int mHistory;
    private final int maxIter;
    private final double gradTol;
    private final double fTol;
    private final double maxStep;

    public LBFGSMinimizer(int mHistory, int maxIter, double gradTol, double fTol, double maxStep) {
        this.mHistory = mHistory;
        this.maxIter = maxIter;
        this.gradTol = gradTol;
        this.fTol = fTol;
        this.maxStep = maxStep;
    }

    @SuppressWarnings("unchecked")
    public <T> T minimize(VectorSpace<T> space, CostFunction<T> func, T x0) {
        T x = space.clone(x0, false);
        T grad = space.clone(x0, true);
        T xTrial = space.clone(x0, true);

        double f = func.evaluate(x, grad, true);
        double gradNorm = Math.sqrt(space.dot(grad, grad));
        double gradNorm0 = Math.max(1e-300, gradNorm);

        logger.info("L-BFGS初始化: J0={:.4e} ||∇J0||={:.4e}", f, gradNorm);

        List<T> sList = new ArrayList<>();
        List<T> yList = new ArrayList<>();
        List<Double> rhoList = new ArrayList<>();
        List<Double> alphaList = new ArrayList<>(mHistory);

        T searchDir = space.clone(x0, true);
        T gradPrev = space.clone(x0, true);
        T sTmp = space.clone(x0, true);
        T yTmp = space.clone(x0, true);
        T qVec = space.clone(x0, true);

        for (int k = 0; k < mHistory; k++) { alphaList.add(0.0); }

        int iter;
        for (iter = 0; iter < maxIter; iter++) {
            if (gradNorm < gradTol * gradNorm0) {
                logger.info("L-BFGS收敛(梯度): 迭代{} ||∇J||/||∇J0||={:.2e}<{:.2e}",
                        iter, gradNorm / gradNorm0, gradTol);
                break;
            }
            computeLBFGSdirection(searchDir, grad, sList, yList, rhoList, alphaList, qVec, space);
            double alpha = lineSearch(func, space, x, grad, f, searchDir, xTrial);

            if (alpha < 1e-16) {
                logger.warn("L-BFGS线搜索失败(alpha={:.2e})，在迭代{}处终止", alpha, iter);
                break;
            }

            for (int i = 0; i < ((ModelState) x).getTotalPoints(); i++) {
                // no-op handled below
            }

            space.addScaled(sTmp, searchDir, alpha, null);
            space.addScaled(x, x, 1.0, sTmp);
            space.addScaled(gradPrev, grad, 1.0, null);
            double fNew = func.evaluate(x, grad, true);
            space.addScaled(yTmp, grad, 1.0, null);
            space.addScaled(yTmp, yTmp, 1.0, null);
            space.addScaled(yTmp, grad, 1.0, gradPrev);
            for (int i = 0; i < ((ModelState) yTmp).getTotalPoints(); i++) {
                // no-op
            }
            double sy = space.dot(sTmp, yTmp);
            if (sy > 1e-20) {
                if (sList.size() >= mHistory) {
                    sList.remove(0);
                    yList.remove(0);
                    rhoList.remove(0);
                }
                sList.add(space.clone(sTmp, false));
                yList.add(space.clone(yTmp, false));
                rhoList.add(1.0 / sy);
            }

            double deltaF = Math.abs(fNew - f) / (1.0 + Math.abs(fNew));
            f = fNew;
            gradNorm = Math.sqrt(space.dot(grad, grad));

            if ((iter + 1) % 5 == 0 || iter == 0) {
                logger.info("L-BFGS迭代{}: J={:.4e} ΔJ/J={:.2e} ||∇J||={:.4e} α={:.3e} nHist={}",
                        iter + 1, f, deltaF, gradNorm, alpha, sList.size());
            }
            if (deltaF < fTol) {
                logger.info("L-BFGS收敛(代价函数): 迭代{} ΔJ/J={:.2e}<{:.2e}",
                        iter + 1, deltaF, fTol);
                break;
            }
        }

        if (iter >= maxIter) {
            logger.warn("L-BFGS达到最大迭代次数 {}，最终 J={:.4e} ||∇J||={:.4e}",
                    maxIter, f, gradNorm);
        }
        return x;
    }

    private <T> void computeLBFGSdirection(T dir, T grad, List<T> sList, List<T> yList,
                                           List<Double> rhoList, List<Double> alphaList,
                                           T qVec, VectorSpace<T> space) {
        int m = sList.size();
        space.addScaled(qVec, grad, 1.0, null);
        for (int i = m - 1; i >= 0; i--) {
            double a = rhoList.get(i) * space.dot(sList.get(i), qVec);
            alphaList.set(i, a);
            space.addScaled(qVec, qVec, 1.0, yList.get(i));
            space.scale(qVec, 1.0);
            space.addScaled(qVec, qVec, 1.0, null);
            T yScaled = space.clone(yList.get(i), false);
            space.scale(yScaled, -a);
            space.addScaled(qVec, qVec, 1.0, yScaled);
        }
        double gamma = 1.0;
        if (m > 0) {
            double yy = space.dot(yList.get(m - 1), yList.get(m - 1));
            double sy = space.dot(sList.get(m - 1), yList.get(m - 1));
            gamma = sy / Math.max(1e-300, yy);
        }
        space.addScaled(dir, qVec, gamma, null);
        for (int i = 0; i < m; i++) {
            double beta = rhoList.get(i) * space.dot(yList.get(i), dir);
            double factor = alphaList.get(i) - beta;
            T sScaled = space.clone(sList.get(i), false);
            space.scale(sScaled, factor);
            space.addScaled(dir, dir, 1.0, sScaled);
        }
        space.scale(dir, -1.0);
    }

    private <T> double lineSearch(CostFunction<T> func, VectorSpace<T> space,
                                   T x, T grad, double f0, T p, T xTrial) {
        double c1 = 1e-4, c2 = 0.9;
        double slope0 = space.dot(grad, p);
        if (slope0 > 0) {
            logger.warn("线搜索: 初始上升方向 slope={:.3e}，反向", slope0);
            space.scale(p, -1.0);
            slope0 = -slope0;
        }
        double alpha0 = 0.0;
        double alpha = Math.min(1.0, maxStep);
        double fPrev = f0;
        double alphaPrev = 0.0;

        T gTrial = space.clone(x, true);

        for (int ls = 0; ls < 30; ls++) {
            space.addScaled(xTrial, x, 1.0, null);
            T pScaled = space.clone(p, false);
            space.scale(pScaled, alpha);
            space.addScaled(xTrial, xTrial, 1.0, pScaled);

            double fTrial = func.evaluate(xTrial, gTrial, false);
            if (!Double.isFinite(fTrial)) {
                alpha *= 0.3;
                continue;
            }
            if (fTrial > f0 + c1 * alpha * slope0 || (ls > 0 && fTrial >= fPrev)) {
                return zoom(func, space, x, grad, f0, slope0, p, xTrial, gTrial,
                        alphaPrev, fPrev, alpha, fTrial, c1, c2);
            }
            double slopeTrial = space.dot(gTrial, p);
            if (Math.abs(slopeTrial) <= -c2 * slope0) {
                return alpha;
            }
            if (slopeTrial >= 0) {
                return zoom(func, space, x, grad, f0, slope0, p, xTrial, gTrial,
                        alpha, fTrial, alphaPrev, fPrev, c1, c2);
            }
            alphaPrev = alpha;
            fPrev = fTrial;
            alpha = Math.min(alpha * 2.0, maxStep);
            if (alpha >= maxStep) return alpha;
        }
        logger.warn("线搜索达到最大迭代，返回 alpha={:.3e}", alpha);
        return alpha;
    }

    private <T> double zoom(CostFunction<T> func, VectorSpace<T> space,
                            T x, T grad, double f0, double slope0, T p, T xTrial, T gTrial,
                            double aLo, double fLo, double aHi, double fHi,
                            double c1, double c2) {
        T pScaled = space.clone(p, true);
        for (int i = 0; i < 20; i++) {
            double delta = aHi - aLo;
            double aTry = 0.5 * (aLo + aHi);
            if (Math.abs(delta) < 1e-14) return aTry;
            if (fHi > fLo) {
                double fa = fHi - fLo - 3 * (fHi - fLo) / (aHi - aLo) * (aHi - aLo);
                double denom = 2 * (fHi - fLo - (fHi - fLo) / (aHi - aLo) * (aHi - aLo));
                if (Math.abs(denom) > 1e-30 && aTry > aLo + 0.1 * delta && aTry < aHi - 0.1 * delta) {
                    // keep interpolation result if reasonable
                }
            }
            space.scale(pScaled, 0);
            space.addScaled(xTrial, x, 1.0, null);
            space.addScaled(pScaled, p, aTry, null);
            space.addScaled(xTrial, xTrial, 1.0, pScaled);
            double fTry = func.evaluate(xTrial, gTrial, false);
            if (!Double.isFinite(fTry)) {
                aHi = aTry; fHi = fTry; continue;
            }
            if (fTry > f0 + c1 * aTry * slope0 || fTry >= fLo) {
                aHi = aTry; fHi = fTry;
            } else {
                double slopeTry = space.dot(gTrial, p);
                if (Math.abs(slopeTry) <= -c2 * slope0) return aTry;
                if (slopeTry * (aHi - aLo) >= 0) { aHi = aLo; fHi = fLo; }
                aLo = aTry; fLo = fTry;
            }
        }
        return 0.5 * (aLo + aHi);
    }

    @FunctionalInterface
    public interface CostFunction<T> {
        double evaluate(T x, T gradOut, boolean computeGrad);
    }
}
