"""Plot real FULL-block A/B maps and measured canopy profiles, never mock terrain."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
from compare_forest_ecotone import load


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("comparisons",nargs="+",type=Path)
    parser.add_argument("--libraries",type=Path)
    parser.add_argument("--output",required=True,type=Path)
    args=parser.parse_args()
    if args.libraries:sys.path.insert(0,str(args.libraries))
    import numpy as np
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import ListedColormap
    fig,axes=plt.subplots(len(args.comparisons),3,figsize=(15,5*len(args.comparisons)),squeeze=False)
    shas=set()
    for row,path in enumerate(args.comparisons):
        comparison=json.loads(path.read_text());shas.add(comparison["jar_sha256"])
        assert len(comparison["cases"])==1,"One natural window per row"
        case=comparison["cases"][0]
        for column,mode in enumerate(["native","ecotone"]):
            lab=Path(case[mode+"_metrics"]).parent
            summary=json.loads((lab/"summary.json").read_text())
            window=summary["windows"][0]
            rows,canopy,roots,shape,_=load(lab,window,np)
            forest=np.array([r["biome"]=="minecraft:forest"for r in rows]).reshape(shape)
            plains=np.array([r["biome"]=="minecraft:plains"for r in rows]).reshape(shape)
            states=np.full(shape,3,dtype=np.uint8);states[plains]=0;states[forest]=1;states[canopy]=2
            ax=axes[row,column]
            ax.imshow(states,origin="lower",interpolation="nearest",cmap=ListedColormap(["#e8e2ca","#c8ddbb","#316844","#d8e7ee"]),vmin=0,vmax=3)
            ax.contour(forest.astype(float),levels=[.5],colors=["#2a5fa7"],linewidths=1)
            zz,xx=np.nonzero(roots);ax.scatter(xx,zz,c="#101c18",s=4)
            title="Нативное число попыток"if mode=="native"else"Общий бюджет + нативные проверки"
            ax.set_title("Сид "+str(comparison["seed"])+"\n"+title,fontsize=11,pad=8)
            ax.set_xlabel("X, блоки внутри участка");ax.set_ylabel("Z, блоки внутри участка")
        ax=axes[row,2]
        bands=case["distance_bands"]
        for mode,color,label in [("native","#a66b44","Нативное число попыток"),("ecotone","#316844","Общий бюджет")]:
            xs=[sum(b["distance_blocks"])/2 for b in bands]
            ys=[100*b[mode]["canopy_fraction"]for b in bands]
            ax.plot(xs,ys,"o-",color=color,label=label,linewidth=1.8,markersize=4)
        ax.axvline(0,color="#2a5fa7",linewidth=1)
        ax.set_xlabel("Расстояние от фактической границы, блоки\nЛес < 0; равнина > 0")
        ax.set_ylabel("Покрытие пологом, % колонок")
        ax.set_ylim(0,100);ax.grid(alpha=.2);ax.legend(fontsize=8)
        ax.set_title("Фактический полог по полосам расстояния",fontsize=11)
    assert len(shas)==1,"Each map must use the same compared final archive"
    fig.suptitle("Лесная опушка: реальные нативные FULL-блоки",fontsize=16,y=.985)
    fig.text(.5,.018,"Фон: бежевый — равнина, светло-зелёный — лес, голубой — прочие биомы; тёмно-зелёный — полог.\n"
             "Синяя линия — граница биомов; тёмные точки — опорные колонки стволов.\n"
             "Карта измерений, не снимок клиента. Один JAR в каждой паре: "+next(iter(shas))[:16]+"…",ha="center",fontsize=10)
    fig.tight_layout(rect=(0,.075,1,.955));args.output.parent.mkdir(parents=True,exist_ok=True)
    fig.savefig(args.output,dpi=160);plt.close(fig)
    print(args.output)


if __name__=="__main__":main()
